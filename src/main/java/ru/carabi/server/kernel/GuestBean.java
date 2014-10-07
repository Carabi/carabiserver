package ru.carabi.server.kernel;

import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.xml.ws.Holder;
import org.apache.commons.codec.digest.DigestUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.UserServerEnter;
import ru.carabi.server.kernel.oracle.QueryParameter;
import ru.carabi.server.kernel.oracle.SqlQueryBean;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.soap.GuestSesion;
import ru.carabi.server.soap.SoapUserInfo;

@Stateless
public class GuestBean {
	private static final Logger logger = Logger.getLogger("ru.carabi.server.kernel.GuestBean");
	
	Context ctx;

	private AuthorizeBean authorize;
	
	@EJB private UsersControllerBean uc;

	@EJB private SqlQueryBean sqlQueryBean;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	private static final ResourceBundle messages = ResourceBundle.getBundle("ru.carabi.server.soap.Messages");

	public GuestBean() throws NamingException {
		ctx = new InitialContext();
	}

	/**
	 * Возвращает ИД веб-пользователя по токену.
	 * @param ul данные выполненной аутентификации
	 * @return ИД веб-пользователя
	 * @throws ru.carabi.server.RegisterException если пользователь не найден
	 */
	public String getWebUserId(UserLogon ul) throws RegisterException
	{
		logger.log(Level.INFO, "ru.carabi.server.kernel.GuestBean.getWebUserId called with param: UserLogon={0}", 
				ul.toString());
		
		// prepare sql request
		String script = 
			"begin\n" +
			"  documents.REGISTER_USER("+String.valueOf(ul.getId())+",2);\n"+
			"  :result := appl_web_user.get_web_user_id(documents.GET_USER_ID);\n"+
			"end;";
		QueryParameter qp = new QueryParameter();
		qp.setName("result");
		qp.setType(QueryParameter.Type.NUMBER);
		qp.setIsIn(QueryParameter.FALSE);
		qp.setIsOut(QueryParameter.TRUE);
		Holder<ArrayList<QueryParameter>> params = new Holder<>();
		params.value = new ArrayList<>();
		params.value.add(qp);
		
		// try to read webuserId from db
		final int execRes = sqlQueryBean.executeScript(ul, script, params, 1);
		if (execRes < 0) {
			final RegisterException e = new RegisterException(RegisterException.MessageCode.ORACLE_ERROR);
			logger.log(Level.SEVERE, "Sql поиска веб-пользователя возвратил код ошибки: "+ execRes, e);
			throw e;
		}
		final String webuserId = qp.getValue();
		if (null == webuserId || "".equals(webuserId)) {
			final String msg = "Sql поиска веб-пользователя возвратил пустой результат. "
			                  +"Веб-пользователь не найден для данного караби-пользователя.";
			final RegisterException e = new RegisterException(RegisterException.MessageCode.NO_WEBUSER);
			logger.log(Level.SEVERE, msg, e);
			throw e;
		}
		
		return webuserId; // success, we have it
	}

	/**
	 * Предварительная авторизация.
	 * Первый этап двухэтапной авторизации.
	 * Производит проверку версии, сохранение данных о клиенте в памяти, генерацию timestamp
	 * (шифровального дополнения к паролю), проверка доступности БД
	 * @param login Имя пользователя
	 * @param version Версия клиента
	 * @param vc Проверять версию клиента
	 * @param schemaID ID целевой схемы Oracle (-1 для выбора по названию)
	 * @param schemaName Название целевой схемы Oracle (пустое значение для выбора основной схемы)
	 * @param timestamp Выходной параметр &mdash; выдача шифровального ключа для передачи пароля на втором этапе
	 * @return Код возврата:
	 *	<ul>
	 *		<li><strong>0</strong> при успешном выполнении</li>
	 *		<li>{@link Settings#VERSION_MISMATCH}, если vc != 0 и version != {@link Settings#projectVersion}</li>
	 *	</ul>
	 */
	public int wellcomeNN(
			final String login,
			String version,
			int vc,
			int schemaID,
			final String schemaName,
			Holder<String> timestamp,
			GuestSesion guestSesion
		) throws RegisterException {
		guestSesion.setLogin(login);
		timestamp.value = "";
		guestSesion.setTimeStamp("");
		guestSesion.setSchemaName(schemaName.trim());
		guestSesion.setSchemaID(schemaID);
		
//		try {
		boolean versionControl = vc != 0;
		if ((!Settings.projectVersion.equals(version)) && versionControl) {
			logger.log(Level.INFO, messages.getString("versionNotFitInfo"), login);
			throw new RegisterException(RegisterException.MessageCode.VERSION_MISMATCH);
		}
		int cnt = (int)(Math.random() * 1000 + 1000);
		StringBuilder timestampBuilder = new StringBuilder(cnt);
		for (int i=0; i<cnt; i++) {
			timestampBuilder.append((char)(Math.random() * 90 + 32));
		}
		timestampBuilder.append(DateFormat.getInstance().format(new Date()));
		timestamp.value = timestampBuilder.toString();
		guestSesion.setTimeStamp(Settings.projectName + "1024" + Settings.serverName + timestamp.value);
			//Проверка доступности БД - надо?
//		} catch (Exception e) {
//			logger.log(Level.INFO, messages.getString("welcomeError"), login);
//			logger.log(Level.SEVERE, "", e);
//			return Settings.SQL_ERROR;
//		}
		return 0;
	}
	
	public String getWebUserInfo(UserLogon ul) throws CarabiException {
		return String.format("{\"login\":\"%s\", \"idCarabiUser\":\"%d\", \"idWebUser\":\"%s\"}",
			ul.userLogin(),
			ul.getId(),
			getWebUserId(ul)
		);
	}
	
	/**
	 * Полная авторизация пользователя. (перенесено из webservice)
	 * Выполняется после предварительной. Создаёт долгоживущую сессию Oracle.
	 * @param user пользователь, найденный в Derby по логину
	 * @param passwordTokenClient ключ -- md5 (md5(login+password)+ (Settings.projectName + "9999" + Settings.serverName timestamp из welcome)) *[login и md5 -- в верхнем регистре]
	 * @param connectionProperties свойства подключения клиента (для журналирования). Должны содержать ipAddrWhite, ipAddrGrey и serverContext.
	 * @param version Номер версии
	 * @param vc Проверять номер версии
	 * @param schemaID
	 * @param info
	 * @return код результата. 0(ноль) - успех, отрицательный - ошибка. 
	 */
	public int registerUser(
			CarabiUser user,
			String passwordTokenClient,
			Properties connectionProperties,
			String version,
			int vc,//Номер версии
			Holder<Integer> schemaID, //Порядковый номер схемы БД
			Holder<SoapUserInfo> info, //Результат
			GuestSesion guestSesion
		) throws CarabiException, RegisterException {
		String login = user.getLogin();
		try {
			authorize = (AuthorizeBean) ctx.lookup("java:module/AuthorizeBean");
			boolean versionControl = vc != 0;
			if ((!Settings.projectVersion.equals(version)) && versionControl) {
				logger.log(Level.INFO, messages.getString("versionNotFitInfo"), login);
				throw new RegisterException(RegisterException.MessageCode.VERSION_MISMATCH);
			}
			logger.log(Level.INFO, messages.getString("registerStart"), login);
			//Получаем пользователя по имени из схемы с нужным номером или названием
			UserLogon userLogon = createUserLogon(guestSesion.getSchemaID(), guestSesion.getSchemaName(), user);
			//Сверяем пароль
			logger.log(Level.INFO, "passwordCipher: {0}", userLogon.getUser().getPassword());
			logger.log(Level.INFO, "timestamp: {0}", guestSesion.getTimeStamp());
			String passwordTokenServer = DigestUtils.md5Hex(userLogon.getUser().getPassword() + guestSesion.getTimeStamp());
			logger.log(Level.INFO, "passwordTokenServer: {0}", passwordTokenServer);
			logger.log(Level.INFO, "passwordTokenClient: {0}", passwordTokenClient);
			if (!passwordTokenClient.equalsIgnoreCase(passwordTokenServer)) {
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_DERBY);
			}
//			passwordTokenServer = DigestUtils.md5Hex(userLogon.getPasswordCipher() + guestSesion.getTimeStamp());
//			if (!passwordTokenClient.equalsIgnoreCase(passwordTokenServer)) {
//				CarabiLogging.logError(messages.getString("registerRefusedDetailsPass"),
//						new Object[]{login, "Oracle " + authorize.getSchema().getSysname(), passwordTokenServer, passwordTokenClient},
//						authorize.getConnection(), true, Level.WARNING, null);
//				authorize.closeConnection();
//				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_ORACLE);
//			}
			//Запоминаем пользователя
			userLogon.setGreyIpAddr(connectionProperties.getProperty("ipAddrGrey"));
			userLogon.setWhiteIpAddr(connectionProperties.getProperty("ipAddrWhite"));
			userLogon.setServerContext(connectionProperties.getProperty("serverContext"));
			String token = authorize.authorizeUser(true);
			//Готовим информацию для возврата
			SoapUserInfo soapUserInfo = new SoapUserInfo();//authorize.createSoapUserInfo();
			soapUserInfo.token = token;
			info.value = soapUserInfo;
			schemaID.value = -1;//currentUser.getSchema().getId();
		} catch (CarabiException e) {
			if (!RegisterException.class.isInstance(e)) {
				CarabiLogging.logError("GuestService.registerUser failed with Exception. ", null, null, false, Level.SEVERE, e);
			}
			throw e;
		} catch (NamingException | SQLException e) {
			CarabiLogging.logError("GuestService.registerUser failed with Exception. ", null, null, false, Level.SEVERE, e);
			throw new CarabiException(e);
		} catch (Exception e) {
			CarabiLogging.logError("GuestService.registerUser failed with UNKNOWN Exception. ", null, null, false, Level.SEVERE, e);
			throw new CarabiException(e);
		} finally {
			if (authorize != null) authorize.remove();
			authorize = null;
		}
		return 0;
	}
	
	/**
	 * Облегчённая авторизация. (перенесено из webservice)
	 * Одноэтапная авторизация -- для использования PHP-скриптами.
	 * @param user Запись о пользователе Carabi
	 * @param passwordCipherClient Зашифрованный пароль
	 * @param requireSession Требуется долгоживущая сессия Oracle
	 * @param connectionProperties свойства подключения клиента (для журналирования). Должны содержать ipAddrWhite, ipAddrGrey и serverContext.
	 * @param schemaName Псевдоним базы Carabi, к которой нужно подключиться (если не задан -- возвращается основная)
	 * @param token Выход: Ключ для авторизации при выполнении последующих действий
	 * @return ID Carabi-пользователя
	 */
	public int registerUserLight(
			CarabiUser user,
			String passwordCipherClient,
			boolean requireSession,
			Properties connectionProperties,
			Holder<String> schemaName,
			Holder<String> token
		) throws CarabiException {
		logger.log(Level.INFO,
				   "GuestService.registerUserLight called with params: user={0}, password={1}, "
				   +"requireSession={2}, schemaName={3}", 
				   new Object[]{user.getLogin(), passwordCipherClient, requireSession, schemaName.value});
		try {
			String login = user.getLogin();
			authorize = (AuthorizeBean) ctx.lookup("java:module/AuthorizeBean");
			if (schemaName.value == null || schemaName.value.isEmpty()) {
				final ConnectionSchema defaultSchema = user.getDefaultSchema();
				if (defaultSchema == null || defaultSchema.getSysname() == null || defaultSchema.getSysname().isEmpty()) {
					throw new RegisterException(RegisterException.MessageCode.NO_SCHEMA);
				}
				schemaName.value = defaultSchema.getSysname();
				logger.log(Level.INFO, "User {0} got schema {1} as default", new Object[] {login, schemaName.value});
			}
			//Перед входом в Oracle сверяем пароль по Derby
			if (!user.getPassword().equalsIgnoreCase(passwordCipherClient)) {
				CarabiLogging.logError(messages.getString("registerRefusedDetailsPass"),
						new Object[]{login, "Apache Derby", user.getPassword(), passwordCipherClient},
						null, false, Level.WARNING, null);
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_DERBY);
			}
			//Получаем пользователя по имени из схемы с нужным названием
			UserLogon logon = createUserLogon(-1, schemaName.value, user);
			logon.setGreyIpAddr(connectionProperties.getProperty("ipAddrGrey"));
			logon.setWhiteIpAddr(connectionProperties.getProperty("ipAddrWhite"));
			logon.setServerContext(connectionProperties.getProperty("serverContext"));
			logger.log(Level.INFO, "По имени схемы и логину ({0}, {1}) получен пользователь: {2}", 
					new Object[] {schemaName, login, String.valueOf(logon)});
			//Сверяем пароль
			if (!passwordCipherClient.equalsIgnoreCase(logon.getPasswordCipher())) {
				CarabiLogging.logError(messages.getString("registerRefusedDetailsPass"),
						new Object[]{login, "Oracle " + authorize.getSchema().getSysname(), logon.getPasswordCipher(), passwordCipherClient},
						authorize.getConnection(), true, Level.SEVERE, null);
				authorize.closeConnection();
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_ORACLE);
			}
			//Запоминаем пользователя
			token.value = authorize.authorizeUser(requireSession);
			logger.log(Level.INFO, "Пользователю выдан токен: {0}", token.value);
			return logon.getId();
		} catch (CarabiException ex) {
			if (RegisterException.class.isInstance(ex)) {
				throw ex;
			}
			CarabiLogging.logError("GuestService.registerUserLight failed with Exception. ", null, null, false, Level.SEVERE, ex);
			throw ex;
		} catch (Exception ex) {
			CarabiLogging.logError("GuestService.registerUserLight failed with Exception. ", null, null, false, Level.SEVERE, ex);
			throw new CarabiException(ex);
		} finally {
			if (authorize != null) authorize.remove();
			authorize = null;
		}
	}
	
	/**
	 * Аналог registerUserLight без подключения к Oracle
	 * @param user Запись о пользователе Carabi
	 * @param passwordCipherClient Зашифрованный пароль
	 * @param token Выход: Ключ для авторизации при выполнении последующих действий
	 * @return ID Carabi-пользователя
	 */
	public int registerGuestUser(
			CarabiUser user,
			String passwordCipherClient,
			Holder<String> token
		) throws RegisterException, CarabiException {
		logger.log(Level.INFO,
				   "GuestService.registerGuestUser called with params: user={0}, password={1}", 
				   new Object[]{user.getLogin(), passwordCipherClient});
		try {
			String login = user.getLogin();
			//сверяем пароль по Derby
			if (!user.getPassword().equalsIgnoreCase(passwordCipherClient)) {
				CarabiLogging.logError(messages.getString("registerRefusedDetailsPass"),
						new Object[]{login, "Apache Derby", user.getPassword(), passwordCipherClient},
						null, false, Level.WARNING, null);
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_DERBY);
			}
			//Создаём сессию
			UserLogon logon = new UserLogon();
			logon.setUser(user);
			logon.setDisplay(user.getFirstname() + " " + user.getMiddlename() + " " + user.getLastname());
			logon.setAppServer(Settings.getCurrentServer());
			logon.setRequireSession(false);
			//Запоминаем пользователя
			logon.updateLastActive();
			logon = uc.addUser(logon);
			token.value = logon.getToken();
			logger.log(Level.INFO, "Пользователю выдан токен: {0}", token.value);
			return logon.getId();
		} catch (CarabiException ex) {
			CarabiLogging.logError("GuestService.registerUserLight failed with Exception. ", null, null, false, Level.SEVERE, ex);
			throw ex;
		} catch (Exception ex) {
			CarabiLogging.logError("GuestService.registerUserLight failed with Exception. ", null, null, false, Level.SEVERE, ex);
		} finally {
			if (authorize != null) authorize.remove();
			authorize = null;
		}
		return 0;
	}
	
	private UserLogon createUserLogon(int schemaID, String schemaName, CarabiUser user) throws CarabiException, NamingException, SQLException {
		//Подключаемся к БД: по названию, по ID или к основной
//		logger.info("try to connect");
		//authorize.connectToDatabase(schemaID, schemaName, user);
		authorize.setCurrentUser(user);
//		logger.info("connected to Oracle");
		//Проверяем наличие пользователя, получаем данные о нём
		boolean userExists = authorize.searchCurrentUser();
		if (!userExists) {
			logger.log(Level.INFO, messages.getString("registerRefused"), user.getLogin());
			CarabiLogging.logError(messages.getString("registerRefusedDetailsBase"),
					new Object[]{user.getLogin(), "Oracle " + authorize.getSchema().getSysname()},
					authorize.getConnection(), true, Level.WARNING, null);
		//	authorize.closeConnection();
			throw new RegisterException(RegisterException.MessageCode.NO_LOGIN_ORACLE);
		}
		return authorize.createUserLogon();
	}
	
	public CarabiUser searchUserInDerby(String login) throws RegisterException {
		CarabiUser user;
		try {
			//получаем запись о пользователе из Derby
			TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfo", CarabiUser.class);
			activeUser.setParameter("login", login);
			user = activeUser.getSingleResult();
		} catch (NoResultException ex) {
			CarabiLogging.logError(messages.getString("registerRefusedDetailsBase"),
					new Object[]{login, "Apache Derby"},
				null, false, Level.INFO, null);
			throw new RegisterException(RegisterException.MessageCode.NO_LOGIN_DERBY);
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
