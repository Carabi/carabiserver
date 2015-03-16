package ru.carabi.server.kernel;

import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Timer;
import javax.ejb.TransactionAttribute;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceUnit;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.UserRole;
import ru.carabi.server.kernel.oracle.CursorFetcherBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Единичный объект со списком активных пользователей.
 * Авторизация аутентифицированных пользователей с выдачей токена, аутентификация по токену,
 * деавторизация по команде и по таймауту.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Singleton
public class UsersControllerBean {
	private static final Logger logger = CarabiLogging.getLogger(UsersControllerBean.class);
	
	//Активные пользователи в соответствии с токенами.
	private final Map<String, UserLogon> activeUsers = new ConcurrentHashMap<>();
	
	@PersistenceUnit(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManagerFactory emf;//использование PersistenceContext в данном случае подвело (сломались прокрутки)
	private EntityManager em;
	
	@EJB private ConnectionsGateBean connectionsGate;
	@EJB private CursorFetcherBean cursorFetcher;
	@EJB private MonitorBean monitor;
	@EJB private Cache cache;
	
	@PostConstruct
	public void postConstruct() {
		em = emf.createEntityManager();
	}
	
	/**
	 * Добавление в систему активного пользователя.
	 * Генерация токена, занесение в служебную БД и список активных пользователей,
	 * передача ссылки на {@link ConnectionsGateBean} для восстановления разорванных подключений.
	 * @param logon
	 * @return Авторизованный пользователь (с установленным токеном и созданным журналом)
	 */
	public synchronized UserLogon addUser(UserLogon logon) {
		String token = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
		//Генерируем случайный токен. Убеждаемся, что он не совпадает с уже используемыми
		while (activeUsers.get(token) != null || em.find(UserLogon.class, token) != null) {
			token = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
			logger.warning("tokenCollision");
		}
		logon.setToken(token);
		logon.setConnectionsGate(connectionsGate);
		logon = updateLastActive(logon, true);
		activeUsers.put(token, logon);
		if (logon.getSchema() != null) {
			logger.log(Level.FINEST, "put {0} to activeUsers in Add", token);
			logon.openCarabiLog();
		}
		logger.log(Level.FINE, "{0}-th user added!", activeUsers.size());
		return logon;
	}
	
	/**
	 * Получение зарегистрированного пользователя по токену
	 * @param token
	 * @return Пользователь с данным токеном, null, если такого пользователя нет
	 */
	@TransactionAttribute
	public UserLogon getUserLogon(String token) {
		if (StringUtils.isEmpty(token)) {
			return null;
		}
		UserLogon logon = activeUsers.get(token);
		if (logon != null) {
			logger.log(Level.FINEST, "got {0} from activeUsers", token);
			return logon;
		}
		logon = em.find(UserLogon.class, token);
		if (logon != null) {
			//JPA может сохранить в кеше подключения к Oracle, что приводит к сбоям.
			try {
				logon.closeAllConnections();
			} catch (SQLException | NullPointerException e) {
				logger.log(Level.INFO, "error on closing lost connection: ", e);
			}
		}
		logger.log(Level.FINEST, "got {0} from JPA em", token);
		return logon;
	}
	
	/**
	 * Удаление авторизованного пользователя.
	 * @param token Токен удаляемого пользователя
	 * @param permanently Удалить так же запись в БД
	 */
	@TransactionAttribute
	public void removeUser(String token, boolean permanently) {
		UserLogon logon = activeUsers.get(token);
		//Удаление из ядра
		if (logon != null) {
			logger.log(Level.FINEST, "removing active user with token {0}", token);
			removeActiveUser(logon);
		} else {
			logger.log(Level.FINE, "no active user with token {0}", token);
		}
		if (!permanently) {
			return;
		}
		//При вызове процедуры пользователь мог быть удалён из ядра,
		//но из базы удалить надо
		em.joinTransaction();
		if (logon == null) {
			logon = em.find(UserLogon.class, token);
		}
		if (logon != null) {
			em.remove(logon);
			em.flush();
		}
	}
	
	/**
	 * Удаление из ядра неактивных пользователей.
	 * Каджую минуту из ядра удаляются сессии пользователей, не активные более чем
	 * {@link Settings.SESSION_LIFETIME} секунд. Их Oracle-сессии закрываются,
	 * в базу вносятся пометки о неактивности.
	 */
	@Schedule(minute="*/1", hour="*")
	@TransactionAttribute
	public synchronized void dispatcheActiveUsers(Timer timer) {
		//Создаём новую коллекцию, чтобы не возникал ConcurrentModificationException
		ArrayList<String> usersTokens = new ArrayList(activeUsers.keySet());
		long timestamp = new Date().getTime();
		for (String userToken: usersTokens) {
			UserLogon logon = activeUsers.get(userToken);
			long lastActiveTimestamp = logon.getLastActive().getTime();
			if (timestamp - lastActiveTimestamp > Settings.SESSION_LIFETIME * 1000) {
				removeActiveUser(logon);
			}
		}
	}
	
	/**
	 * Удаление из базы давно не заходивших пользователей.
	 * Раз в день из базы удаляются сессии, не активные более чем 
	 * {@link Settings.TOKEN_LIFETIME} дней.
	 * @param timer 
	 */
	@Schedule(hour="4")
	@TransactionAttribute
	public synchronized void dispatcheUsersInBase(Timer timer) {
		CarabiAppServer currentServer = Settings.getCurrentServer();
		if (currentServer.isMaster()) {//чисткой базы должен заниматься единственный сервер
			em.joinTransaction();
			Query query = em.createNamedQuery("deleteOldLogons");
			//Удаляем записи о давно не заходивших пользователях
			Calendar calendar = new GregorianCalendar();
			calendar.add(GregorianCalendar.DAY_OF_MONTH, -Settings.TOKEN_LIFETIME);
			query.setParameter("long_ago", calendar.getTime());
			logger.log(Level.INFO, "delete users older than: {0}", calendar.getTime().toString());
			query.executeUpdate();
			em.flush();
		}
	}
	/**
	 * Удаление активной пользовательской сессии.
	 * Закрытие её кеша, прокруток и подключений к Oracle.
	 * @param logon закрываемая сессия
	 */
	private void removeActiveUser(UserLogon logon) {
		cache.removeUserData(logon.getToken());
		if (logon.getSchema() != null) {
			try {
				cursorFetcher.closeAllFetches(logon);
			} catch (Exception ex) {
				Logger.getLogger(UsersControllerBean.class.getName()).log(Level.SEVERE, null, ex);
			} finally {
				try {
					CarabiLogging.closeUserLog(logon, Utls.unwrapOracleConnection(logon.getConnection()));
					logon.closeAllConnections();
				} catch (SQLException ex) {
					Logger.getLogger(UsersControllerBean.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		}
		activeUsers.remove(logon.getToken());
		logger.fine("removed user");
		logger.log(Level.FINE, "{0} users left", activeUsers.size());
	}
	/**
	 * Проверка наличия токена в системе,
	 * получение учётки без подключения к Oracle
	 * @param token
	 * @return
	 * @throws CarabiException 
	 */
	public UserLogon tokenControl(String token) throws CarabiException {
		UserLogon logon = getUserLogon(token);
		if (logon == null) {
			final String msg = "Ошибка безопастности сервера. Токен '"+token+"' неизвестен, "
			                  +"пользователь не аутентифицирован в системе";
			logger.log(Level.INFO, msg);
			throw new RegisterException(RegisterException.MessageCode.NO_TOKEN);
		} else {
			logger.log(Level.FINE, "tokenAuthorize, lastActive in base: {0}", DateFormat.getInstance().format(logon.getLastActive()));
			if (!logon.isPermanent()) {
				logon.setAppServer(Settings.getCurrentServer());
			}
			logon = updateLastActive(logon, false);
		}
		return logon;
	}
	
	@TransactionAttribute
	private UserLogon updateLastActive(UserLogon logon, boolean logonIsNew) {
		logon.updateLastActive();
		if (logonIsNew || monitor.getKernelDBLockcount() == 0) {
			em.joinTransaction();
			logon = em.merge(logon);
			em.merge(logon.getUser());
			em.flush();
		}
		return logon;
	}
	/**
	 * Авторизация по токену.
	 * Поиск пользователя с данным токеном среди активных и в служебной БД.
	 * Продление активности и срока действия токена.
	 * создание сессии Oracle, если была сброшена с указанием о повторном
	 * создании, если требовалась долгоживущая.
	 * @param token
	 * @return UserLogon - данные регистрации пользователя в системе
	 * @throws CarabiException если пользователя с заданным токеном нет
	 */
	public UserLogon tokenAuthorize(String token, boolean connectToOracle) throws CarabiException {
		UserLogon logon = tokenControl(token);
		if (!activeUsers.containsKey(token)) {
			activeUsers.put(token, logon);
			logger.log(Level.FINEST, "put {0} to activeUsers in TokenAuth", token);
		}
		logon.setConnectionsGate(connectionsGate);
		if (connectToOracle && logon.getSchema() != null) {
			if (!logon.checkConnection()) {
				logger.warning("could not get connection at first time");
			}
		}
		return logon;
	}
	
	public UserLogon tokenAuthorize(String token) throws CarabiException {
		return tokenAuthorize(token, true);
	}
	
	/**
	 * Получение списка активных пользователей.
	 * Выдаёт список логинов пользователей, которые в данный момент в системе.
	 * @return список логинов
	 */
	public List<String> getActiveUsers() {
		List<String> result = new ArrayList();
		for (UserLogon activeUser: activeUsers.values()) {
			result.add(activeUser.userLogin());
		}
		return result;
	}
	
	
	/**
	 * Поиск пользователя по логину
	 * @param login логин
	 * @return найденный пользователь
	 * @throws CarabiException если пользователь не найден
	 */
	public CarabiUser findUser(String login) throws CarabiException {
		try {
			TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfo", CarabiUser.class);
			activeUser.setParameter("login", login);
			CarabiUser user = activeUser.getSingleResult();
			return user;
		} catch (NoResultException ex) {
			final CarabiException e = new CarabiException("No user with login " 
					+ login);
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
	}
	
	/**
	 * Поиск пользователя по email
	 * @param email email
	 * @return найденный пользователь
	 * @throws CarabiException если пользователь не найден или найдено несколько
	 */
	public CarabiUser getUserByEmail(String email) throws CarabiException {
		CarabiUser user;
		try { //Пробуем найти пользователя с логином, равным указанному email
			TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfo", CarabiUser.class);
			activeUser.setParameter("login", email);
			user = activeUser.getSingleResult();
		} catch (NoResultException ex) {// если не нашли -- ищем по полю email
			try {
				TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfoByEmail", CarabiUser.class);
				activeUser.setParameter("email", email);
				user = activeUser.getSingleResult();
			} catch (NoResultException e) {
				throw new CarabiException("User with email " + email + " not found");
			} catch (NonUniqueResultException e) {
				throw new CarabiException("More than one users with email " + email);
			}
		}
		return user;
	}
	
	public void close() {
		ArrayList<String> usersTokens = new ArrayList(activeUsers.keySet());
		for (String userToken: usersTokens) {
			removeUser(userToken, false);
		}
	}
	
	/**
	 * Проверка, имеет ли текущий пользователь указанное право.
	 */
	public boolean userHavePermission(UserLogon logon, String permission) throws CarabiException {
		final CarabiUser user = logon.getUser();
		return userHavePermission(user, permission);
	}
	
	public boolean userHavePermission(String login, String permission) throws CarabiException {
		final CarabiUser user = findUser(login);
		return userHavePermission(user, permission);
	}
	
	/**
	 * Проверка, имеет ли данный пользователь указанное право.
	 * Принцип действия:
	 * <ul>
	 * <li>1 Если разрешение или запрет указано непосредственно для пользователя (таблица USER_HAS_PERMISSION) -- вернуть его.
	 * <li>2 Если нет, то искать по группам:
	 * <li>2.1 Если хотя бы в одной группе указано разрешение и ни в одной не указан запрет -- вернуть разрешение
	 * <li>2.2 Если хотя бы в одной группе указан запрет и ни в одной не указано разрешение -- вернуть запрет
	 * <li>3 Иначе (нет данных или противоречие) -- вернуть настройку для права по умолчанию.
	 * </ul>
	 * @param user пользователь
	 * @param permission кодовое название права
	 * @return имеет ли данный пользователь указанное право
	 */
	public boolean userHavePermission(CarabiUser user, String permission) throws CarabiException {
		//проверка, что запрашиваемое право существует в системе
		String sql = "select ALLOWED_BY_DEFAULT from CARABI.USER_PERMISSION where SYSNAME = ?";
		Query query = em.createNativeQuery(sql);
		query.setParameter(1, permission);
		List resultList = query.getResultList();
		if (resultList.isEmpty()) {
			throw new CarabiException("Permission " + permission + " not found");
		}
		Number permissionAllowedByDefault = (Number) resultList.get(0);
		//проверка права конкретно для пользователя
		sql = "select USER_HAS_PERMISSION.PERMISSION_INHIBITED from USER_PERMISSION inner join USER_HAS_PERMISSION on USER_PERMISSION.PERMISSION_ID = USER_HAS_PERMISSION.PERMISSION_ID\n" +
		"where USER_HAS_PERMISSION.USER_ID = ? and USER_PERMISSION.SYSNAME = ?";
		query = em.createNativeQuery(sql);
		query.setParameter(1, user.getId());
		query.setParameter(2, permission);
		resultList = query.getResultList();
		if (!resultList.isEmpty()) { //Выборка может содержать 0 строк (разрешения или запрета нет) или 1 (есть).
			Number permissionInhibited = (Number) resultList.get(0);
			return permissionInhibited.intValue() == 0;
		}
		//Ищем по ролям
		List<Boolean> rolesPermissions = new ArrayList<>();
		for (UserRole role: user.getRoles()) {
			sql = "select PERMISSION_INHIBITED from ROLE_HAS_PERMISSION inner join USER_PERMISSION on ROLE_HAS_PERMISSION.PERMISSION_ID = USER_PERMISSION.PERMISSION_ID\n" +
			"where ROLE_HAS_PERMISSION.ROLE_ID = ? and USER_PERMISSION.SYSNAME = ?";
			query = em.createNativeQuery(sql);
			query.setParameter(1, role.getId());
			query.setParameter(2, permission);
			resultList = query.getResultList();
			if (!resultList.isEmpty()) {
				Number permissionInhibited = (Number) resultList.get(0);
				rolesPermissions.add(permissionInhibited.intValue() == 0);
			}
		}
		//проверям, что в ролях указано только разрешение или только запрет
		if (!rolesPermissions.isEmpty()) {
			boolean firstRolePermission = rolesPermissions.get(0);
			boolean returnRolePermission = true;
			for (boolean rolesPermission: rolesPermissions) {
				if (rolesPermission != firstRolePermission) {
					returnRolePermission = false;
					break;
				}
			}
			if (returnRolePermission) {
				return firstRolePermission;
			}
		}
		//в ролях нет данных или противоречие -- берём умолчание из права
		return permissionAllowedByDefault.intValue() > 0;
	}
}
