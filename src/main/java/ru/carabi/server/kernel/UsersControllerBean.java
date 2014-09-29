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
import javax.persistence.PersistenceUnit;
import javax.persistence.Query;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiAppServer;
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
	private EntityManager em;//Доступ через JPA -- только для служебной информации в Derby
	
	@EJB private ConnectionsGateBean connectionsGate;
	@EJB private CursorFetcherBean cursorFetcher;
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
	@TransactionAttribute
	public synchronized UserLogon addUser(UserLogon logon) {
		String token = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
		//Генерируем случайный токен. Убеждаемся, что он не совпадает с уже используемыми
		while (activeUsers.get(token) != null || em.find(UserLogon.class, token) != null) {
			token = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
			logger.warning("tokenCollision");
		}
		logon.setToken(token);
		final DateFormat dateFormat = DateFormat.getInstance();
		logger.info("addUser, lastActive: " + dateFormat.format(logon.getLastActive()));
		logon.updateLastActive();
		logger.info("addUser, updatedLastActive: " + dateFormat.format(logon.getLastActive()));
		logon.setConnectionsGate(connectionsGate);
		em.joinTransaction();
		logon = em.merge(logon);
		em.flush();
		logger.info("addUser, lastActive in base: " + dateFormat.format(logon.getLastActive()));
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
			logon.updateLastActive();
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
			UserLogon user = activeUsers.get(userToken);
			long lastActiveTimestamp = user.getLastActive().getTime();
			if (timestamp - lastActiveTimestamp > Settings.SESSION_LIFETIME * 1000) {
				removeActiveUser(user);
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
	@TransactionAttribute
	public UserLogon tokenControl(String token) throws CarabiException {
		UserLogon logon = getUserLogon(token);
		if (logon == null) {
			final String msg = "Ошибка безопастности сервера. Токен '"+token+"' неизвестен, "
			                  +"пользователь не аутентифицирован в системе";
			logger.log(Level.INFO, msg);
			throw new RegisterException(RegisterException.MessageCode.NO_TOKEN);
		} else {
			logger.info("tokenAuthorize, lastActive in base: " + DateFormat.getInstance().format(logon.getLastActive()));
			em.joinTransaction();
			logon.updateLastActive();
			if (!logon.isPermanent()) {
				logon.setAppServer(Settings.getCurrentServer());
			}
			logon = em.merge(logon);
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

	public void close() {
		ArrayList<String> usersTokens = new ArrayList(activeUsers.keySet());
		for (String userToken: usersTokens) {
			removeUser(userToken, false);
		}
	}
}
