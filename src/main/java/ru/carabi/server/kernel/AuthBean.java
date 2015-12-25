package ru.carabi.server.kernel;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.xml.ws.Holder;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.UserServerEnter;
import ru.carabi.server.logging.CarabiLogging;

@Stateless
public class AuthBean {
	private static final Logger logger = Logger.getLogger("ru.carabi.server.kernel.GuestBean");
	
	private AuthorizeSecondary authorize = new AuthorizeSecondaryAbstract();
	
	@EJB private UsersControllerBean usersController;
	
	@EJB private ConnectionsGateBean connectionsGate;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	HashMap<String, ?> userInfo;
	
	private static final ResourceBundle messages = ResourceBundle.getBundle("ru.carabi.server.soap.Messages");
	
//	/**
//	 * Предварительная авторизация.
//	 * Первый этап двухэтапной авторизации.
//	 * Производит проверку версии, сохранение данных о клиенте в памяти, генерацию timestamp
//	 * (шифровального дополнения к паролю), проверка доступности БД
//	 * @param login Имя пользователя
//	 * @param authSesion
//	 * @return 
//	 */
//	public String welcome(
//			final String login,
//			AuthSesion authSesion
//		) {
//		authSesion.setLogin(login);
//		int cnt = (int)(Math.random() * 1000 + 1000);
//		StringBuilder timestampBuilder = new StringBuilder(cnt);
//		for (int i=0; i<cnt; i++) {
//			timestampBuilder.append((char)(Math.random() * 90 + 32));
//		}
//		timestampBuilder.append(DateFormat.getInstance().format(new Date()));
//		String timestamp = timestampBuilder.toString();
//		authSesion.setTimeStamp(Settings.projectName + "9999" + Settings.serverName + timestamp);
//		return timestamp;
//	}
//	
//	/**
//	 * Полная авторизация пользователя.
//	 * Выполняется после предварительной. Создаёт долгоживущую сессию Oracle.
//	 * @param user пользователь, найденный в ядровой базе по логину
//	 * @param passwordTokenClient ключ -- md5 (md5(login+password)+ (Settings.projectName + "9999" + Settings.serverName timestamp из welcome)) *[login и md5 -- в верхнем регистре]
//	 * @param userAgent название программы-клиента
//	 * @param connectionProperties свойства подключения клиента (для журналирования). Должны содержать ipAddrWhite, ipAddrGrey и serverContext.
//	 * @param schemaName
//	 * @param authSesion
//	 * @return токен
//	 */
//	public String createToken(
//			CarabiUser user,
//			String passwordTokenClient,
//			String userAgent,
//			Properties connectionProperties,
//			String schemaName,
//			AuthSesion authSesion
//		) throws CarabiException, RegisterException {
//		String login = user.getLogin();
//		try {
//			logger.log(Level.INFO, messages.getString("registerStart"), login);
//			//Создаём сессию для пользователя с привязкой к нужной вторичной БД
//			UserLogon logon = createUserLogon(-1, schemaName, user, userAgent);
//			//Сверяем пароль
//			logger.log(Level.INFO, "passwordCipher: {0}", logon.getUser().getPassword());
//			logger.log(Level.INFO, "timestamp: {0}", authSesion.getTimeStamp());
//			String passwordTokenServer = DigestUtils.md5Hex(logon.getUser().getPassword() + authSesion.getTimeStamp());
//			logger.log(Level.INFO, "passwordTokenServer: {0}", passwordTokenServer);
//			logger.log(Level.INFO, "passwordTokenClient: {0}", passwordTokenClient);
//			passwordTokenServer = DigestUtils.md5Hex(logon.getUser().getPassword() + authSesion.getTimeStamp());
//			if (!passwordTokenClient.equalsIgnoreCase(passwordTokenServer)) {
//				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_KERNEL);
//			}
//			//Запоминаем пользователя
//			logon.setGreyIpAddr(connectionProperties.getProperty("ipAddrGrey"));
//			logon.setWhiteIpAddr(connectionProperties.getProperty("ipAddrWhite"));
//			logon.setServerContext(connectionProperties.getProperty("serverContext"));
//			String token = authorizeUserLogon(logon, true);
//			return token;
//		} catch (CarabiException e) {
//			if (!RegisterException.class.isInstance(e)) {
//				CarabiLogging.logError("GuestService.registerUser failed with Exception. ", null, null, false, Level.SEVERE, e);
//			}
//			throw e;
//		} catch (NamingException | SQLException e) {
//			CarabiLogging.logError("GuestService.registerUser failed with Exception. ", null, null, false, Level.SEVERE, e);
//			throw new CarabiException(e);
//		} catch (Exception e) {
//			CarabiLogging.logError("GuestService.registerUser failed with UNKNOWN Exception. ", null, null, false, Level.SEVERE, e);
//			throw new CarabiException(e);
//		}
//	}
	
	/**
	 * Облегчённая авторизация.
	 * Одноэтапная авторизация -- для использования PHP-скриптами.
	 * @param user Запись о пользователе Carabi
	 * @param passwordHash хеш вида md5(login.uppercase + password).uppercase
	 * @param userAgent название программы-клиента
	 * @param connectionProperties свойства подключения клиента (для журналирования). Должны содержать ipAddrWhite, ipAddrGrey и serverContext.
	 * @param continuousConnection требуется долгоживущая сессия Oracle
	 * @param schemaName Псевдоним вторичной базы данных, к которой нужно подключиться (если не задан -- возвращается основная)
	 * @return ID Carabi-пользователя
	 */
	public String createTokenSimple(
			CarabiUser user,
			String passwordHash,
			String userAgent,
			Properties connectionProperties,
			boolean continuousConnection,
			Holder<String> schemaName
		) throws CarabiException {
		logger.log(Level.INFO,
				   "GuestService.createTokenSimple called with params: user={0}, passwordHash={1}, "
				   +"userAgent={2}, continuousConnection={3} schemaName={4}", 
				   new Object[]{user.getLogin(), passwordHash, userAgent, continuousConnection, schemaName.value});
		try {
			String login = user.getLogin();
			if (schemaName.value == null || schemaName.value.isEmpty()) {
				final ConnectionSchema defaultSchema = user.getDefaultSchema();
				if (defaultSchema != null) {
					schemaName.value = defaultSchema.getSysname();
					logger.log(Level.INFO, "User {0} got schema {1} as default", new Object[] {login, schemaName.value});
				}
			}
			//Сверяем пароль
			if (!user.getPassword().equalsIgnoreCase(passwordHash)) {
				CarabiLogging.logError(messages.getString("registerRefusedDetailsPass"),
						new Object[]{login, "carabi_kernel", user.getPassword(), passwordHash},
						null, false, Level.WARNING, null);
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_KERNEL);
			}
			UserLogon logon = createUserLogon(-1, schemaName.value, user, userAgent);
			logon.setGreyIpAddr(connectionProperties.getProperty("ipAddrGrey"));
			logon.setWhiteIpAddr(connectionProperties.getProperty("ipAddrWhite"));
			logon.setServerContext(connectionProperties.getProperty("serverContext"));
			//Запоминаем пользователя
			String token = authorizeUserLogon(logon, continuousConnection);
			logger.log(Level.INFO, "Пользователю выдан токен: {0}", token);
			return token;
		} catch (CarabiException ex) {
			if (RegisterException.class.isInstance(ex)) {
				throw ex;
			}
			CarabiLogging.logError("GuestService.registerUserLight failed with Exception. ", null, null, false, Level.SEVERE, ex);
			throw ex;
		} catch (Exception ex) {
			CarabiLogging.logError("GuestService.registerUserLight failed with Exception. ", null, null, false, Level.SEVERE, ex);
			throw new CarabiException(ex);
		}
	}
	
	private UserLogon createUserLogon(int schemaID, String schemaName, CarabiUser user, String userAgent) throws CarabiException, NamingException, SQLException {
		ConnectionSchema schema = connectionsGate.getDedicatedSchema(schemaID, schemaName, user.getLogin());
		UserLogon logon = new UserLogon();
		logon.setUser(user);
		logon.setDisplay(authorize.getUserDisplayString(user, userInfo));
		logon.setSchema(schema);
		logon.setAppServer(Settings.getCurrentServer());
		logon.setUserAgent(userAgent);
		return logon;
	}
	
	private String authorizeUserLogon(UserLogon logon, boolean requireSession) {
		logon.setRequireSession(requireSession);
		logon = usersController.addUser(logon);
		return logon.getToken();
	}
	
	/**
	 * Поиск пользователя в ядровой базе.
	 * @param login логин
	 * @return найденный пользователь
	 * @throws RegisterException пользователя с таким логином нет.
	 */
	public CarabiUser searchUser(String login) throws RegisterException {
		CarabiUser user;
		try {
			//получаем запись о пользователе из ядровой базы
			TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfo", CarabiUser.class);
			activeUser.setParameter("login", login);
			user = activeUser.getSingleResult();
		} catch (NoResultException ex) {
			CarabiLogging.logError(messages.getString("registerRefusedDetailsBase"),
					new Object[]{login, "carabi_kernel"},
				null, false, Level.INFO, null);
			throw new RegisterException(RegisterException.MessageCode.NO_LOGIN_KERNEL);
		}
		return user;
	}
	
	/**
	 * Внесение данных о входе на сервер в статистику.
	 * Проверка, установлен ли у пользователя основной сервер. Если нет -- 
	 * установка текущего основным. Так же создание/наращивание счётчика
	 * посещаемости для текущего сервера.
	 */
	public CarabiUser checkCurrentServer(CarabiUser user) {
		final CarabiAppServer currentServer = Settings.getCurrentServer();
		TypedQuery<UserServerEnter> getUserServerEnter = em.createNamedQuery("getUserServerEnter", UserServerEnter.class);
		getUserServerEnter.setParameter("user", user);
		getUserServerEnter.setParameter("server", currentServer);
		List<UserServerEnter> userServerEnterList = getUserServerEnter.getResultList();
		UserServerEnter userServerEnter;
		if (userServerEnterList.isEmpty()) {
			userServerEnter = new UserServerEnter();
			userServerEnter.setUser(user);
			userServerEnter.setServer(currentServer);
		} else {
			userServerEnter = userServerEnterList.get(0);
		}
		userServerEnter.increment();
		em.merge(userServerEnter);
		
		if (user.getMainServer() == null) {
			user.setMainServer(currentServer);
			user = em.merge(user);
		}
		em.flush();
		return user;
	}
}
