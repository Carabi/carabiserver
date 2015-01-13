package ru.carabi.server.soap;

import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.handler.MessageContext;
import ru.carabi.server.CarabiException;
import ru.carabi.server.CarabiOracleMessage;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.kernel.ConnectionsGateBean;
import ru.carabi.server.kernel.GuestBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Сервис для неавторизованных пользователей.
 * Содержит методы авторизации. Имеется два способа авторизации:
 * <ul>
 * <li>
 *		двухэтапный ({@linkplain #wellcomeNN}, {@linkplain #registerUser}) &ndash;
 *		для настольных приложений, создающих долгоживущую сессию и получающих информацию о клиенте;
 * </li>
 * <li>
 *		одноэтапный ({@linkplain #registerUserLight}) &ndash; для web-приложений,
 *		мобильных приложений, любых кратковременных подключений.
 * </li>
 * </ul>
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "GuestService")
public class GuestService {
	private static final Logger logger = CarabiLogging.getLogger(GuestService.class);
	@EJB 
	private UsersControllerBean usersController;
	@EJB 
	private GuestBean guest;
	@EJB 
	private ConnectionsGateBean cg;

	@Inject
	GuestSesion guestSesion;
	
	@Resource
	private WebServiceContext context;
	private static final ResourceBundle messages = ResourceBundle.getBundle("ru.carabi.server.soap.Messages");
	
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
	@WebMethod(operationName = "wellcomeNN")
	public int wellcomeNN(
			@WebParam(name = "login") final String login,
			@WebParam(name = "version") String version,
			@WebParam(name = "vc") int vc,
			@WebParam(name = "schemaID") int schemaID,
			@WebParam(name = "schemaName") final String schemaName,
			@WebParam(name = "timestamp", mode = WebParam.Mode.OUT) Holder<String> timestamp
		) throws RegisterException {
		return guest.wellcomeNN(login, version, vc, schemaID, schemaName, timestamp, guestSesion);
	}

	/**
	 * Полная авторизация пользователя.
	 * Выполняется после предварительной. Создаёт долгоживущую сессию Oracle.
	 * @param login логин
	 * @param passwordTokenClient ключ &mdash; md5 (md5(login+password)+ (Settings.projectName + "9999" + Settings.serverName timestamp из welcome)) *[login и md5 -- в верхнем регистре]
	 * @param clientIp локальный IP-адрес клиентского компьютера (для журналирования)
	 * @param version Номер версии
	 * @param vc Проверять номер версии
	 * @param schemaID
	 * @param info
	 * @return код результата. 0(ноль) &mdash; успех, отрицательный &mdash; ошибка. 
	 */
	@WebMethod(operationName = "registerUser")
	public int registerUser(
			@WebParam(name = "login") String login,
			@WebParam(name = "password") String passwordTokenClient,
			@WebParam(name = "clientIp") String clientIp,
			@WebParam(name = "version") String version,
			@WebParam(name = "vc") int vc,//Номер версии
			@WebParam(name = "schemaID", mode = WebParam.Mode.OUT) Holder<Integer> schemaID, //Порядковый номер схемы БД
			@WebParam(name = "info", mode = WebParam.Mode.OUT) Holder<SoapUserInfo> info//Результат
		) throws CarabiException, RegisterException {
		try {
			logger.log(Level.INFO, "cg.tryConnectToOracle({0}, {1}, {2});", new Object[]{guestSesion.getSchemaID(), guestSesion.getSchemaName(), login});
			cg.tryConnectToOracle(guestSesion.getSchemaID(), guestSesion.getSchemaName(), login);
		} catch (Exception ex) {
			logger.log(Level.SEVERE, null, ex);
		}
		try {
			CarabiUser user = guest.searchUserInDerby(login);
			user = guest.checkCurrentServer(user);
			return guest.registerUser(user, passwordTokenClient, getConnectionProperties(clientIp), version, vc, schemaID, info, guestSesion);
		} catch (RegisterException e) {
			if (e.badLoginPassword()) {
				logger.log(Level.INFO, "", e);
				throw new RegisterException(RegisterException.MessageCode.ILLEGAL_LOGIN_OR_PASSWORD);
			} else {
				logger.log(Level.SEVERE, "", e);
				throw e;
			}
		}
	}
	
	/**
	 * Облегчённая авторизация.
	 * Одноэтапная авторизация &mdash; для использования PHP-скриптами.
	 * @param login Имя пользователя Carabi
	 * @param passwordCipherClient Зашифрованный пароль
	 * @param requireSession Требуется долгоживущая сессия Oracle
	 * @param clientIp локальный IP-адрес клиента (при использовании для Web &mdash; адрес браузера) &mdash; для журналирования.
	 * @param schemaName Псевдоним базы Carabi, к которой нужно подключиться (если не задан &mdash; возвращается основная)
	 * @param token Выход: Ключ для авторизации при выполнении последующих действий
	 * @return ID Carabi-пользователя
	 */
	@WebMethod(operationName = "registerUserLight")
	public int registerUserLight(
			@WebParam(name = "login") String login,
			@WebParam(name = "password") String passwordCipherClient,
			@WebParam(name = "requireSession") boolean requireSession,
			@WebParam(name = "clientIp") String clientIp,
			@WebParam(name = "schemaName", mode = WebParam.Mode.INOUT) Holder<String> schemaName,
			@WebParam(name = "token", mode = WebParam.Mode.OUT) Holder<String> token
		) throws RegisterException, CarabiException {
		logger.log(Level.INFO, "{0} is logining", login);
		try {
			CarabiUser user = guest.searchUserInDerby(login);
			user = guest.checkCurrentServer(user);
			cg.tryConnectToOracle(-1, schemaName.value, login);
			return guest.registerUserLight(user, passwordCipherClient, requireSession, getConnectionProperties(clientIp), schemaName, token);
		} catch (RegisterException e) {
			if (e.badLoginPassword()) {
				logger.log(Level.INFO, "", e);
				throw new RegisterException(RegisterException.MessageCode.ILLEGAL_LOGIN_OR_PASSWORD);
			} else {
				logger.log(Level.SEVERE, "", e);
				throw e;
			}
		}
	}
	
	/**
	 * Аналог registerUserLight без подключения к Oracle
	 * @param login Имя пользователя Carabi
	 * @param passwordCipherClient Зашифрованный пароль
	 * @param token Выход: Ключ для авторизации при выполнении последующих действий
	 * @return ID Carabi-пользователя
	 */
	@WebMethod(operationName = "registerGuestUser")
	public int registerGuestUser(
			@WebParam(name = "login") String login,
			@WebParam(name = "password") String passwordCipherClient,
			@WebParam(name = "token", mode = WebParam.Mode.OUT) Holder<String> token
		) throws RegisterException, CarabiException {
		logger.log(Level.INFO, "{0} is logining", login);
		CarabiUser user = guest.searchUserInDerby(login);
		user = guest.checkCurrentServer(user);
		return guest.registerGuestUser(user, passwordCipherClient, token);
	}
	
	/**
	 * Получение информации о текущем пользователе.
	 * проверка, кто авторизован с данным токеном, и получение основных данных:
	 * логин, ID Carabi-пользователя, схема
	 * @param token токен сессии
	 * @return {"login":"%s", "base":"%s" "carabiUserID":"%d"}
	 * @throws CarabiException если пользователь не найден
	 */
	@WebMethod(operationName = "getUserInfo")
	public String getUserInfo(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token, false)) {
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("login", logon.getUser().getLogin());
			ConnectionSchema schema = logon.getSchema();
			if (schema != null) {
				result.add("schema", schema.getSysname());
			} else {
				result.addNull("schema");
			}
			result.add("carabiUserID", logon.getId());
			return result.build().toString();
		}
	}
	/**
	 * Получение информации о текущем пользователе с веб-аккаунтом.
	 * проверка, кто авторизован с данным токеном, и получение основных данных:
	 * ID Carabi-пользователя, Web-пользователя, логин
	 * ID веб-пользователя по его ID Караби пользователя 
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @throws CarabiException если токен или веб-аккаунт не найден
	 * @return JSON-объект с полями login, idCarabiUser, idWebUser
	 */
	@WebMethod(operationName = "getWebUserInfo")
	public String getWebUserInfo(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon ul = usersController.tokenAuthorize(token)) {
			return guest.getWebUserInfo(ul);
		}
	}
	
	@WebMethod(operationName = "getOracleUserID")
	public int getOracleUserID(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return logon.getId();
		}
	}
	
	/**
	 * Деавторизация.
	 * Удаление объекта с долгоживущей сессией (при наличии),
	 * удаление записи из служебной БД -- опционально
	 * @param token Токен выходящего пользователя
	 * @param permanently Удалить запись из служебной БД
	 */
	@WebMethod(operationName = "unauthorize")
	public void unauthorize(
			@WebParam(name = "token") String token,
			@WebParam(name = "permanently") boolean permanently
		) 
	{
		usersController.removeUser(token, permanently);
	}
	
	/**
	 * Получение данных о сервере.
	 * Не требует авторизации. Так же может
	 * использоваться для проверки работоспособности.
	 * @return 
	 */
	public String about() {
		return guest.about();
	}
	
	/**
	 * Создание объекта Properties для передачи в методы GuestBean.
	 * @param greyIP серый IP, переданный клиентом
	 * @return свойства подключения с полями:<ul>
	 * <li>ipAddrWhite &mdash; белый IP клиента, определённый сервером
	 * <li>ipAddrGrey &mdash; параметр greyIP
	 * <li>serverContext &mdash; адрес сервера, включающий IP с портом (белый &mdash; заданный
	 * в свойствах контейнера jndi/ServerWhiteAddress, при его отсутствии &mdash; серый)
	 * и имя JavaEE программы</ul>
	 */
	private Properties getConnectionProperties(String greyIP) {
		Properties connectionProperties = new Properties();
		if (greyIP == null) {
			greyIP = "null";
		}
		connectionProperties.setProperty("ipAddrGrey", greyIP);
		HttpServletRequest req = (HttpServletRequest)context.getMessageContext().get(MessageContext.SERVLET_REQUEST);
		connectionProperties.setProperty("ipAddrWhite", req.getRemoteAddr());
		try {
			Context initialContext = new InitialContext();
			String serverName = (String) initialContext.lookup("jndi/ServerName");
			logger.log(Level.INFO, "serverName: {0}", serverName);
		} catch (NamingException ex) {
			logger.log(Level.SEVERE, "serverName not found", ex);
		}
		CarabiAppServer currentServer = Settings.getCurrentServer();
		String serverIpPort = currentServer.getComputer()+ ":" + currentServer.getGlassfishPort();
		connectionProperties.setProperty("serverContext", serverIpPort + req.getContextPath());
		return connectionProperties;
	}
}
