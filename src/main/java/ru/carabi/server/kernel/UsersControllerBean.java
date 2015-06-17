package ru.carabi.server.kernel;

import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Timer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Permission;
import ru.carabi.server.entities.SoftwareProduct;
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
	
	@EJB private UsersPercistenceBean usersPercistence;
	@EJB private ConnectionsGateBean connectionsGate;
	@EJB private CursorFetcherBean cursorFetcher;
	@EJB private MonitorBean monitor;
	@EJB private Cache cache;
	
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
		while (activeUsers.get(token) != null || usersPercistence.findUserLogon(token) != null) {
			token = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
			logger.warning("tokenCollision");
		}
		logon.setToken(token);
		logon.setConnectionsGate(connectionsGate);
		logon = usersPercistence.updateLogon(logon);
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
	public UserLogon getUserLogon(String token) {
		if (StringUtils.isEmpty(token)) {
			return null;
		}
		UserLogon logon = activeUsers.get(token);
		if (logon != null) {
			logger.log(Level.FINEST, "got {0} from activeUsers", token);
			return logon;
		}
		logon = usersPercistence.findUserLogon(token);
		if (logon != null) {
			logger.log(Level.FINEST, "got {0} from JPA em", token);
		}
		return logon;
	}
	
	/**
	 * Удаление авторизованного пользователя.
	 * @param token Токен удаляемого пользователя
	 * @param permanently Удалить так же запись в БД
	 */
	public void removeUserLogon(String token, boolean permanently) {
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
		usersPercistence.removeUserLogon(token);
	}
	
	/**
	 * Удаление из ядра неактивных пользователей.
	 * Каджую минуту из ядра удаляются сессии пользователей, не активные более чем
	 * {@link Settings.SESSION_LIFETIME} секунд. Их Oracle-сессии закрываются,
	 * в базу вносятся пометки о неактивности.
	 */
	@Schedule(minute="*/1", hour="*")
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
			logon = usersPercistence.updateLogon(logon);
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
		return usersPercistence.findUser(login);
	}
	
	/**
	 * Поиск пользователя по email
	 * @param email email
	 * @return найденный пользователь
	 * @throws CarabiException если пользователь не найден или найдено несколько
	 */
	public CarabiUser getUserByEmail(String email) throws CarabiException {
		return usersPercistence.findUser(email);
	}
	
	public void close() {
		ArrayList<String> usersTokens = new ArrayList(activeUsers.keySet());
		for (String userToken: usersTokens) {
			removeUserLogon(userToken, false);
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
	 * <li>3 Иначе (если нет противоречия) -- вернуть настройку для права по умолчанию.
	 * </ul>
	 * Если выставлен флаг PERMISSIONS_TRUST в настройках -- всегда возвращается true (например, на случай незаполненной базы прав)
	 * @param user пользователь
	 * @param permission кодовое название права
	 * @return имеет ли данный пользователь указанное право
	 * @throws ru.carabi.server.CarabiException если такого права нет в системе
	 */
	public boolean userHavePermission(CarabiUser user, String permission) throws CarabiException {
		return usersPercistence.userHavePermission(user, permission);
	}
	
	public List<Permission> getUserPermissions(UserLogon logon) {
		return usersPercistence.getUserPermissions(logon);
	}
	
	public List<SoftwareProduct> getAvailableProduction(UserLogon logon) {
		return usersPercistence.getAvailableProduction(logon);
	}
	
	public List<SoftwareProduct> getAvailableProduction(UserLogon logon, String currentProduct) {
		return usersPercistence.getAvailableProduction(logon, currentProduct);
	}
	
}
