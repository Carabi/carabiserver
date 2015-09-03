package ru.carabi.server.soap;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.inject.Inject;
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
import oracle.net.nt.ConnOption;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.kernel.AuthBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Сервис для авторизации пользователей.
 * Имеется два способа авторизации:
 * <ul>
 * <li>
 *		двухэтапный ({@linkplain #wellcomeNN}, {@linkplain #registerUser}) &ndash;
 *		для настольных приложений, создающих долгоживущую сессию и получающих информацию о клиенте;
 * </li>
 * <li>
 *		одноэтапный ({@link #createSession}) &ndash; для web-приложений,
 *		мобильных приложений, любых кратковременных подключений.
 * </li>
 * </ul>
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "AuthService")
public class AuthService {
	private static final Logger logger = CarabiLogging.getLogger(AuthService.class);
	@EJB 
	private UsersControllerBean usersController;
	@EJB 
	private AuthBean auth;

	@Inject
	AuthSesion authSesion;
	
	@Resource
	private WebServiceContext context;
//	
//	/**
//	 * Предварительная авторизация.
//	 * Первый этап двухэтапной авторизации.
//	 * Производит проверку версии, сохранение данных о клиенте в памяти, генерацию timestamp
//	 * (шифровального дополнения к паролю), проверка доступности БД
//	 * @param login Имя пользователя
//	 * @return 
//	 */
//	@WebMethod(operationName = "welcome")
//	public String welcome(@WebParam(name = "login") final String login) throws RegisterException {
//		return auth.welcome(login, authSesion);
//	}
//
//	/**
//	 * Полная авторизация пользователя.
//	 * Выполняется после предварительной. Создаёт долгоживущую сессию Oracle.
//	 * @param login логин
//	 * @param passwordTokenClient ключ &mdash; md5 (md5(login+password)+ (Settings.projectName + "9999" + Settings.serverName timestamp из welcome)) *[login и md5 -- в верхнем регистре]
//	 * @param userAgent название программы-клиента
//	 * @param clientIp локальный IP-адрес клиентского компьютера (для журналирования)
//	 * @param schemaID
//	 * @param info
//	 * @return код результата. 0(ноль) &mdash; успех, отрицательный &mdash; ошибка. 
//	 */
//	@WebMethod(operationName = "registerUser")
//	public String registerUser(
//			@WebParam(name = "login") String login,
//			@WebParam(name = "password") String passwordTokenClient,
//			@WebParam(name = "userAgent") String userAgent,
//			@WebParam(name = "clientIp") String clientIp,
//			@WebParam(name = "schemaName") final String schemaName,
//			@WebParam(name = "autoCloseableConnection") boolean autoCloseableConnection,
//			@WebParam(name = "notConnectToOracle") boolean notConnectToOracle,
//			@WebParam(name = "schemaID", mode = WebParam.Mode.OUT) Holder<Integer> schemaID, //Порядковый номер схемы БД
//			@WebParam(name = "info", mode = WebParam.Mode.OUT) Holder<SoapUserInfo> info//Результат
//		) throws CarabiException, RegisterException {
//		try {
//			logger.log(Level.INFO, "cg.tryConnectToOracle({0}, {1}, {2});", new Object[]{schemaID, schemaName, login});
//		} catch (Exception ex) {
//			logger.log(Level.SEVERE, null, ex);
//		}
//		try {
//			CarabiUser user = auth.searchUser(login);
//			user = auth.checkCurrentServer(user);
//			return auth.registerUser(user, passwordTokenClient, userAgent, getConnectionProperties(clientIp), schemaID, info, authSesion);
//		} catch (RegisterException e) {
//			if (e.badLoginPassword()) {
//				logger.log(Level.INFO, "", e);
//				throw new RegisterException(RegisterException.MessageCode.ILLEGAL_LOGIN_OR_PASSWORD);
//			} else {
//				logger.log(Level.SEVERE, "", e);
//				throw e;
//			}
//		}
//	}
	
	/**
	 * Одноэтапная авторизация.
	 * <p>
	 * Одноэтапная авторизация рекомендуется для использования PHP-скриптами
	 * и другими клиентами, установленными на одной машине с сервером. Использование
	 * удалёнными клиентами без HTTPS потенциально опасно при перехвате login и passwordHash
	 * </p>
	 * <p>
	 * При выполнении данного метода создаётся объект {@link UserLogon}
	 * (пользовательская сессия) и запись в соответствующей таблице ядровой БД.
	 * Возвращается токен &mdash; автогенерируемый ключ для вызова всех методов системы, требующих аутентификации.
	 * Созданная сессия позволяет работать с ядровой БД и с одной из вторичных БД ({@link ConnectionSchema}).
	 * Название вторичной БД указывается в параметре schemaName, при его отсутствии осуществляется
	 * подключение к основной БД пользователя. Если нет ни параметра schemaName, ни основной БД
	 * в настройках пользователя, подключение к вторичной БД не производится.
	 * </p>
	 * @param login Имя пользователя Carabi
	 * @param passwordHash хеш вида md5(login.uppercase + password).uppercase
	 * @param userAgent название программы-клиента
	 * @param clientIp локальный IP-адрес клиента (при использовании для Web &mdash; адрес браузера) &mdash; для журналирования.
	 * @param continuousConnection подключение ко вторичной БД не будет закрываться между операциями --
	 * необходимо для использования прокруток ({@link Fetch}), рекомендуется при частых обращениях к серверу.
	 * @param schemaName Псевдоним вторичной базы данных, к которой нужно подключиться
	 * @return Токен авторизации для последующих операций
	 * @throws ru.carabi.server.RegisterException неверный логин или пароль
	 */
	@WebMethod(operationName = "createTokenSimple")
	public String createTokenSimple(
			@WebParam(name = "login") String login,
			@WebParam(name = "passwordHash") String passwordHash,
			@WebParam(name = "userAgent") String userAgent,
			@WebParam(name = "clientIp") String clientIp,
			@WebParam(name = "continuousConnection") boolean continuousConnection,
			@WebParam(name = "schemaName", mode = WebParam.Mode.INOUT) Holder<String> schemaName
		) throws RegisterException, CarabiException {
		logger.log(Level.INFO, "{0} is logining", login);
		try {
			CarabiUser user = auth.searchUser(login);
			user = auth.checkCurrentServer(user);
			return auth.createTokenSimple(user, passwordHash, userAgent, getConnectionProperties(clientIp), continuousConnection, schemaName);
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
	 * Отключение.
	 * Разрыв текущего соединения с неядровой БД, удаление кеша из оперативной памяти.
	 * token остаётся в ядровой БД, для удаления используйте метод {@link #removeToken(java.lang.String) }
	 * @param token Токен выходящего пользователя
	 */
	@WebMethod(operationName = "disconnect")
	public void disconnect(@WebParam(name = "token") String token) {
		usersController.removeUserLogon(token, false);
	}
	
	/**
	 * Деавторизация.
	 * Отключение ({@link #disconnect(java.lang.String) }) и удаление токена из БД навсегда.
	 * @param token Токен выходящего пользователя
	 */
	@WebMethod(operationName = "unauthorize")
	public void unauthorize(@WebParam(name = "token") String token) {
		usersController.removeUserLogon(token, true);
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
