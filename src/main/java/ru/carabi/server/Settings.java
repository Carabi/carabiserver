package ru.carabi.server;

import ru.carabi.server.entities.CarabiAppServer;
import java.io.File;
import java.io.IOException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceUnit;
import javax.persistence.TypedQuery;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebListener
public class Settings implements ServletContextListener {
	public static final ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.Settings");
	private static ServletContext context = null;
	
	/**
	 * Каталог, куда пишутся логи, раскидываемые по отдельным файлам (обычно для отладки).
	 */
	public static final String LOGS_LOCATION = getLogsLocation(settings.getString("LOGS_LOCATION"), "../logs");
	
	/**
	 * Писать ли Караби-логи в файлы.
	 */
	public static final boolean WRITE_CARABI_LOGS = Boolean.parseBoolean(settings.getString("WRITE_CARABI_LOGS"));
	/**
	 * Каталог, куда дублируются Караби-логи (записываемые так же в Oracle).
	 */
	public static final String CARABI_LOGS_LOCATION = getLogsLocation(settings.getString("CARABI_LOGS_LOCATION"), "../logs/carabi");
	
	/**
	 * каталог, в котором хранятся вложения чата
	 */
	public static final String CHAT_ATTACHMENTS_LOCATION = settings.getString("CHAT_ATTACHMENTS_LOCATION");
	/**
	 * каталог, в котором хранятся пользовательские аватары (оригиналы)
	 */
	public static String AVATARS_LOCATION = settings.getString("AVATARS_LOCATION");
	/**
	 * каталог, в котором хранятся миниатюры аватаров
	 */
	public static String THUMBNAILS_LOCATION = settings.getString("THUMBNAILS_LOCATION");
	
	/**
	 * Максимальный объём оригинала аватара
	 */
	public static int maxAvatarSize = Integer.valueOf(settings.getString("MAX_AVATAR_SIZE")) * 1024 * 1024;
	/**
	 * Максимальный объём вложения чата
	 */
	public static int maxAttachmentSize = Integer.valueOf(settings.getString("MAX_ATTACHMENT_SIZE")) * 1024 * 1024;
	/**
	 * Получение абсолютного пути к каталогу с логами.
	 * Вычисление абсолютного пути из относительного в конфиге. При ошибке возвращает запасной
	 * относительный путь.
	 * @param configRelativePath относительный путь из конфига
	 * @param defaultRelativePath относительный путь по умолчанию
	 * @return абсолютный путь от configRelativePath, если его  удалось вычислить. Если нет -- defaultRelativePath.
	 */
	private static String getLogsLocation(String configRelativePath, String defaultRelativePath) {
		try {
			return new File(configRelativePath).getCanonicalPath();
		} catch (IOException ex) {
			Logger.getLogger(Settings.class.getName()).log(Level.SEVERE, null, ex);
			return defaultRelativePath;
		}
	}
	
	/**
	 * Использовать ли JDBC-пул сервера Glassfish (если нет -- подключаться к базе самостоятельно)
	 */
	public static final boolean USE_SYSTEM_POOL = Boolean.parseBoolean(settings.getString("USE_SYSTEM_POOL"));
	
	public static final boolean CHECK_STORED_QUERY_BASE = Boolean.parseBoolean(settings.getString("CHECK_STORED_QUERY_BASE"));
	
	public static final boolean PERMISSIONS_TRUST = Boolean.parseBoolean(settings.getString("PERMISSIONS_TRUST"));
	/**
	 * Время в секундах, сколько должна жить пользовательская сессия в ядре
	 * без обновления пользователем.
	 */
	public static final int SESSION_LIFETIME = Integer.valueOf(settings.getString("SESSION_LIFETIME"));
	
	/**
	 * Время в сутках, сколько должна жить запись в служебной БД с токеном пользователя.
	 */
	public static final int TOKEN_LIFETIME = Integer.valueOf(settings.getString("TOKEN_LIFETIME"));
	
	/**
	 * Количество символов в авторизационном токене.
	 */
	public static final int TOKEN_LENGTH = Integer.valueOf(settings.getString("TOKEN_LENGTH"));
	
	/**
	 * Сколько открытых курсоров может держать пользователь.
	 */
	public static final int FETCHES_BY_USER = Integer.valueOf(settings.getString("FETCHES_BY_USER"));
	
	/**
	 * Максимальный шаг прокрутки
	 */
	public static final int MAX_FETCH_SIZE = 1000;
	
	/**
	 * Пауза (в секундах) при переподключении к Oracle (должна быть больше "Validate At Most Once" в настройках пула)
	 */
	public static final int RECONNECTION_PAUSE = Integer.valueOf(settings.getString("RECONNECTION_PAUSE"));
	
	/**
	 * Ошибка при парсинге строкового значения, переданного через HTTP (числа, даты и др.)
	 */
	public static final int PARSING_ERROR = -200;
	
		/**
	 * Ошибка при привязке параметров к хранимому запросу (при сохранении в служебной БД)
	 * и при выполнении перед запуском в Oracle
	 */
	public static final int BINDING_ERROR = -203;
//==========================================
	//Взято из Delphi
	public static final String serverName = "CarabiServer";
	public static final String projectName = "MarketPRO";
	public static final String projectVersion = settings.getString("PROJECT_VERSION");

	public static final int SQL_ERROR = -201;
	public static final int SQL_EOF = -202;
	public static final int OPENED_FETCHES_LIMIT_ERROR = -1000;
	//==========================================
	private static CarabiAppServer currentServer;

	public static CarabiAppServer getCurrentServer() {
		if (currentServer != null) {
			return currentServer;
		}
		CarabiLogging.getLogger(Settings.class).warning("currentServer was not init in contextInitialized");
		currentServer = initCurrentServer();
		return currentServer;
	}

	private static CarabiAppServer initCurrentServer() {
		String applServerName = null;
		Context initialContext;
		Logger logger = CarabiLogging.getLogger(Settings.class);
		CarabiAppServer currentServer = null;
		try {
			initialContext = new InitialContext();
			applServerName = (String) initialContext.lookup("jndi/ServerName");
			TypedQuery<CarabiAppServer> findCarabiServerQuery = em.createNamedQuery("findCarabiServer", CarabiAppServer.class);
			findCarabiServerQuery.setParameter("serverName", applServerName);
			currentServer = findCarabiServerQuery.getSingleResult();
		} catch (NamingException ex) {
			logger.log(Level.SEVERE, "ServerName is not set in jndi", ex);
		} catch (NoResultException | NonUniqueResultException ex) {
			logger.log(Level.SEVERE, "data for ServerName " + applServerName + " not found", ex);
		}
		return currentServer;
	}
	
	private static CarabiAppServer masterServer;

	public static CarabiAppServer getMasterServer() {
		if (masterServer != null) {
			return masterServer;
		}
		CarabiLogging.getLogger(Settings.class).warning("masterServer was not init in contextInitialized");
		currentServer = initCurrentServer();
		return currentServer;
	}

	private static CarabiAppServer initMasterServer() {
		Logger logger = CarabiLogging.getLogger(Settings.class);
		CarabiAppServer masterServer = null;
		try {
			TypedQuery<CarabiAppServer> findCarabiServerQuery = em.createNamedQuery("findMasterServer", CarabiAppServer.class);
			masterServer = findCarabiServerQuery.getSingleResult();
		} catch (NoResultException | NonUniqueResultException ex) {
			logger.log(Level.SEVERE, "no or more than one master server in settings", ex);
		}
		return masterServer;
	}

	@PersistenceUnit(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManagerFactory emf;
	private static EntityManager em;
	
	@Override
	public void contextInitialized(ServletContextEvent event) {
		context = event.getServletContext();
		CarabiLogging.contextInitialized(event);
		em = emf.createEntityManager();
		currentServer = initCurrentServer();
		masterServer = initMasterServer();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		me.lima.ThreadSafeDateParser.close();
		CarabiLogging.contextDestroyed(event);
		try {
			Context ctx = new InitialContext();
			UsersControllerBean usersController = (UsersControllerBean) ctx.lookup("java:module/UsersControllerBean");
			usersController.close();
		} catch (NamingException ex) {
			Logger.getLogger(Settings.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}
}
