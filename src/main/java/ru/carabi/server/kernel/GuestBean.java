package ru.carabi.server.kernel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
import org.apache.commons.lang3.RandomStringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.PersonalTemporaryCode;
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
	
	AuthorizeSecondary authorize = new AuthorizeSecondaryAbstract();
	
	@EJB private UsersControllerBean usersController;
	@EJB private UsersPercistenceBean usersPercistence;

	@EJB private SqlQueryBean sqlQueryBean;

	@EJB private ConnectionsGateBean connectionsGate;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	HashMap<String, ?> userInfo;
	
	private static final ResourceBundle messages = ResourceBundle.getBundle("ru.carabi.server.soap.Messages");

	public GuestBean() throws NamingException {
		ctx = new InitialContext();
	}

	/**
	 * Возвращает ID веб-пользователя по сессии.
	 * @param logon данные выполненной аутентификации
	 * @return ID веб-пользователя
	 * @throws ru.carabi.server.RegisterException если пользователь не найден
	 */
	public String getWebUserId(UserLogon logon) throws RegisterException
	{
		logger.log(Level.INFO, "ru.carabi.server.kernel.GuestBean.getWebUserId called with param: UserLogon={0}", 
				logon.toString());
		
		// prepare sql request
		String script = 
			"begin\n" +
			"  documents.REGISTER_USER("+String.valueOf(logon.getExternalId())+",2);\n"+
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
		final int execRes = sqlQueryBean.executeScript(logon, script, params, 1);
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
	 * @param autoCloseableConnection Закрывать Oracle-сессию после каждого вызова -- рекомендуется при редком обращении, если не используются прокрутки
	 * @param notConnectToOracle Не подключаться к Oracle при авторизации (использование данной опции не позволит вернуть подробные данные о пользователе)
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
			boolean autoCloseableConnection,
			boolean notConnectToOracle,
			Holder<String> timestamp,
			GuestSesion guestSesion
		) throws RegisterException {
		guestSesion.setLogin(login);
		timestamp.value = "";
		guestSesion.setTimeStamp("");
		guestSesion.setSchemaName(schemaName.trim());
		guestSesion.setSchemaID(schemaID);
		guestSesion.setRequireSession(!autoCloseableConnection);
		guestSesion.setConnectToOracle(!notConnectToOracle);
		
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
		guestSesion.setTimeStamp(Settings.projectName + "9999" + Settings.serverName + timestamp.value);
		return 0;
	}
	
	public String getWebUserInfo(UserLogon logon) throws CarabiException {
		return String.format("{\"login\":\"%s\", \"idCarabiUser\":\"%d\", \"idWebUser\":\"%s\"}",
			logon.userLogin(),
			logon.getExternalId(),
			getWebUserId(logon)
		);
	}
	
	/**
	 * Полная авторизация пользователя. (перенесено из webservice)
	 * Выполняется после предварительной. Создаёт долгоживущую сессию Oracle.
	 * @param user пользователь, найденный в ядровой базе по логину
	 * @param passwordTokenClient ключ -- md5 (md5(login+password)+ (Settings.projectName + "9999" + Settings.serverName timestamp из welcome)) *[login и md5 -- в верхнем регистре]
	 * @param userAgent название программы-клиента
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
			String userAgent,
			Properties connectionProperties,
			String version,
			int vc,//Номер версии
			Holder<Integer> schemaID, //Порядковый номер схемы БД
			Holder<SoapUserInfo> info, //Результат
			GuestSesion guestSesion
		) throws CarabiException, RegisterException {
		String login = user.getLogin();
		try {
			boolean versionControl = vc != 0;
			if ((!Settings.projectVersion.equals(version)) && versionControl) {
				logger.log(Level.INFO, messages.getString("versionNotFitInfo"), login);
				throw new RegisterException(RegisterException.MessageCode.VERSION_MISMATCH);
			}
			logger.log(Level.INFO, messages.getString("registerStart"), login);
			//Получаем пользователя по имени из схемы с нужным номером или названием
			UserLogon logon = createUserLogon(guestSesion.getSchemaID(), guestSesion.getSchemaName(), user, guestSesion.connectToOracle(), userAgent);
			//Сверяем пароль
			logger.log(Level.INFO, "passwordCipher: {0}", logon.getUser().getPassword());
			logger.log(Level.INFO, "timestamp: {0}", guestSesion.getTimeStamp());
			String passwordTokenServer = DigestUtils.md5Hex(logon.getUser().getPassword() + guestSesion.getTimeStamp());
			logger.log(Level.INFO, "passwordTokenServer: {0}", passwordTokenServer);
			logger.log(Level.INFO, "passwordTokenClient: {0}", passwordTokenClient);
			passwordTokenServer = DigestUtils.md5Hex(logon.getUser().getPassword() + guestSesion.getTimeStamp());
			if (!passwordTokenClient.equalsIgnoreCase(passwordTokenServer)) {
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_KERNEL);
			}
			//Запоминаем пользователя
			logon.setGreyIpAddr(connectionProperties.getProperty("ipAddrGrey"));
			logon.setWhiteIpAddr(connectionProperties.getProperty("ipAddrWhite"));
			logon.setServerContext(connectionProperties.getProperty("serverContext"));
			String token = authorizeUserLogon(logon, guestSesion.requireSession());
			//Готовим информацию для возврата
			SoapUserInfo soapUserInfo = authorize.createSoapUserInfo(userInfo);
			soapUserInfo.token = token;
			info.value = soapUserInfo;
			if (logon.getSchema() != null) {
				schemaID.value = logon.getSchema().getId();
			}
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
		}
		return 0;
	}
	
	/**
	 * Облегчённая авторизация. (перенесено из webservice)
	 * Одноэтапная авторизация -- для использования PHP-скриптами.
	 * @param user Запись о пользователе Carabi
	 * @param passwordCipherClient Зашифрованный пароль
	 * @param userAgent название программы-клиента
	 * @param notConnectToOracle не подключаться к Oracle при авторизации (использование данной опции не позволит вернуть подробные данные о пользователе)
	 * @param requireSession требуется долгоживущая сессия Oracle
	 * @param connectionProperties свойства подключения клиента (для журналирования). Должны содержать ipAddrWhite, ipAddrGrey и serverContext.
	 * @param schemaName Псевдоним базы Carabi, к которой нужно подключиться (если не задан -- возвращается основная)
	 * @param token Выход: Ключ для авторизации при выполнении последующих действий
	 * @return ID Carabi-пользователя
	 */
	public long registerUserLight(
			CarabiUser user,
			String passwordCipherClient,
			String userAgent,
			boolean requireSession,
			boolean notConnectToOracle,
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
			if (schemaName.value == null || schemaName.value.isEmpty()) {
				final ConnectionSchema defaultSchema = user.getDefaultSchema();
				if (defaultSchema == null || defaultSchema.getSysname() == null || defaultSchema.getSysname().isEmpty()) {
					throw new RegisterException(RegisterException.MessageCode.NO_SCHEMA);
				}
				schemaName.value = defaultSchema.getSysname();
				logger.log(Level.INFO, "User {0} got schema {1} as default", new Object[] {login, schemaName.value});
			}
			//Сверяем пароль
			if (!user.getPassword().equalsIgnoreCase(passwordCipherClient)) {
				CarabiLogging.logError(messages.getString("registerRefusedDetailsPass"),
						new Object[]{login, "carabi_kernel", user.getPassword(), passwordCipherClient},
						null, false, Level.WARNING, null);
				throw new RegisterException(RegisterException.MessageCode.BAD_PASSWORD_KERNEL);
			}
			//Получаем пользователя по имени из схемы с нужным названием
			UserLogon logon = createUserLogon(-1, schemaName.value, user, !notConnectToOracle, userAgent);
			logon.setGreyIpAddr(connectionProperties.getProperty("ipAddrGrey"));
			logon.setWhiteIpAddr(connectionProperties.getProperty("ipAddrWhite"));
			logon.setServerContext(connectionProperties.getProperty("serverContext"));
			logger.log(Level.INFO, "По имени схемы и логину ({0}, {1}) получен пользователь: {2}", 
					new Object[] {schemaName, login, String.valueOf(logon)});
			//Запоминаем пользователя
			token.value = authorizeUserLogon(logon, requireSession);
			logger.log(Level.INFO, "Пользователю выдан токен: {0}", token.value);
			return logon.getExternalId();
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
	
	private UserLogon createUserLogon(int schemaID, String schemaName, CarabiUser user, boolean connectToOracle, String userAgent) throws CarabiException, NamingException, SQLException {
		logger.log(Level.FINE, "createUserLogon called with parameters: schemaID={0}, schemaName={1}, user={2}, connectToOracle={3}, userAgent={4}", new Object[]{schemaID, schemaName, user.getLogin(), connectToOracle, userAgent});
		userInfo = null;
		ConnectionSchema schema = connectionsGate.getDedicatedSchema(schemaID, schemaName, user.getLogin());
		if (connectToOracle) {
			try (Connection connection = connectionsGate.connectToSchema(schema)) {
				//Проверяем наличие пользователя в Oracle, получаем доп. данные о нём
				userInfo = authorize.getDetailedUserInfo(connection, user.getLogin());
				if (userInfo == null) {
					logger.log(Level.INFO, messages.getString("registerRefused"), user.getLogin());
					throw new RegisterException(RegisterException.MessageCode.NO_LOGIN_ORACLE);
				}
			}
		}
		UserLogon logon = new UserLogon();
		logon.setExternalId(authorize.getUserID(userInfo));
		logon.setUser(user);
		//final String userDisplayString = authorize.getUserDisplayString(user, userInfo);
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
		CarabiUser user = null;
		if (usersPercistence.userExists(login)) {
			try {
				user = usersPercistence.findUser(login);
			} catch (CarabiException ex) {//shoud not be thrown
				Logger.getLogger(GuestBean.class.getName()).log(Level.SEVERE, null, ex);
			}
		} else {
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
	
	public String about() {
		return "{\"name\":\"Carabi Application Server\", \"branch\":\"free\", \"version\":\"" + Settings.projectVersion + "\"}";
	}
	
	/**
	 * Отправляет по почте код для восстановления пароля пользователю с данным email.
	 * Ищет пользователя с логином, совпадающим со введённым email.
	 * Если таких нет -- с указанным полем email, равным указанному.
	 * Если таких нет или больше одного -- ошибка.
	 * @param email адрес пользователя, пароль которого надо восстановить.
	 */
	public void sendPasswordRecoverCode(String email) throws CarabiException {
		CarabiUser user;
		try {
			user = usersController.getUserByEmail(email);
		} catch (Exception e){
			return;
		}
		//генерируем код восстановления, не совпадающий с существующими
		String code = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
		while (em.find(PersonalTemporaryCode.class, code) != null) {
			code = RandomStringUtils.randomAlphanumeric(Settings.TOKEN_LENGTH);
			logger.warning("codeCollision");
		}
		//Создаём обект со сгенерированным кодом, обрабатываемым пользователем и сроком 1 день
		PersonalTemporaryCode personalTemporaryCode = new PersonalTemporaryCode();
		personalTemporaryCode.setCode(code);
		personalTemporaryCode.setCodeType("password_recover");
		personalTemporaryCode.setTimestamp(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 24));
		personalTemporaryCode.setUser(user);
		personalTemporaryCode = em.merge(personalTemporaryCode);
		em.flush();
		try {
			//Отправляем письмо через PHP Backend
			String data = "mail=" + email + "&type=recovery&code=" + code;
			data = data.replaceAll("@", "%40");
			try (Socket socket = new Socket("127.0.0.1", 3210)) {
				OutputStream outputStream = socket.getOutputStream();
				PrintStream printStream = new PrintStream(outputStream);
				String head = "POST /index.php HTTP/1.1\r\n" +
						"Host: appl.cara.bi\r\n" +
						"Connection: close\r\n" +
						"Content-type: application/x-www-form-urlencoded\r\n" +
						"Content-Length: " + data.getBytes("UTF-8").length + "\r\n";
				printStream.print(head);
				printStream.print("\r\n");
				printStream.println(data);
				InputStream inputStream = socket.getInputStream();
				Utls.skipHttpHeaders(inputStream);
				BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
				String result = bufferedReader.readLine();
				if (!"OK".equals(result)) {
					logger.warning(result);
					throw new CarabiException(result);
				}
			}
		} catch (IOException ex) {
			Logger.getLogger(GuestBean.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	/**
	 * Изменение пароля по коду восстановления.
	 * Ищет пользователя с указанными email и кодом восстановления, если нашли -- ставит указанный пароль
	 * @param email
	 * @param code
	 * @param password
	 * @return удалось ли восстановить пароль
	 */
	public boolean recoverPassword(String email, String code, String password) {
		CarabiUser user;
		try {
			user = usersController.getUserByEmail(email);
		} catch (Exception e){
			return false;
		}
		PersonalTemporaryCode personalTemporaryCode = em.find(PersonalTemporaryCode.class, code);
		if (personalTemporaryCode != null && user.equals(personalTemporaryCode.getUser())) {
			String passwordChipher = DigestUtils.md5Hex(user.getLogin().toUpperCase() + password);
			user.setPassword(passwordChipher.toUpperCase());
			em.merge(user);
			em.remove(personalTemporaryCode);
			em.flush();
			return true;
		} else {
			return false;
		}
	}
}
