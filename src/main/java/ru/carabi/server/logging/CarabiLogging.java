package ru.carabi.server.logging;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;

/**
 * Вспомогательные функции для журналирования.
 * @author sasha<kopilov.ad@gmail.com>
 */
public abstract class CarabiLogging {
	public static final ResourceBundle messages = ResourceBundle.getBundle("ru.carabi.server.logging.Messages");
	private static final Map<String, Logger> errorLoggers = new ConcurrentHashMap<>();
	private static final Map<String, Logger> statisticsLoggers = new ConcurrentHashMap<>();
	
	private static ServletContext context = null;

	public static Logger getLogger(String className) {
		return Logger.getLogger(className);
	}
	
	public static Logger getLogger(Object context) {
		return getLogger(context.getClass().getName());
	}
	
	public static Logger getLogger(Class context) {
		return getLogger(context.getName());
	}
	
	public static Logger getLogger(String className, String logName) {
		Logger logger = getLogger(className + context.getContextPath() + ":" + logName);
		addHandler(logger, Settings.LOGS_LOCATION + "/" + context.getContextPath() + "/" + logName);
		return logger;
	}
	
	public static Logger getLogger(Object context, String logName) {
		return getLogger(context.getClass().getName(), logName);
	}
	
	public static Logger getLogger(Class context, String logName) {
		return getLogger(context.getName(), logName);
	}
	
	//Файлы с логами и их хендлеры
	private static final Map<String, FileHandler> handlers = new ConcurrentHashMap<>();
	//Логеры, пишущие в отдельные файлы
	private static final Set<Logger> loggers = new CopyOnWriteArraySet<>();
	//Созданные каталоги
	private static final Set<String> directories = new CopyOnWriteArraySet<>();
	/**
	 * Вывод логгера в отдельный файл.
	 * Функция может вызываться много раз (логгер с одним именем инициализируется во
	 * многих местах), но вывод в файл не должен дублироваться.
	 * @param logger логгер
	 * @param logLocation файл для вывода (абсолютный путь)
	 */
	private static void addHandler(Logger logger, String logLocation) {
		try {
			//Создаём каталог (путь к файлу) один раз, если его нет
			String directory = logLocation.substring(0, logLocation.lastIndexOf("/"));
			if (!directories.contains(directory)) {
				new File(directory).mkdirs();
				directories.add(directory);
			}
			FileHandler handler;
			if (handlers.containsKey(logLocation)) {
				handler = handlers.get(logLocation);
			} else {
				handler = new FileHandler(logLocation, 1024*1024, 10);
				handler.setFormatter(new CarabiFormatter());
				handlers.put(logLocation, handler);
			}
			loggers.add(logger);
			if (!Arrays.asList(logger.getHandlers()).contains(handler)) {
				logger.addHandler(handler);
				logger.log(Level.FINE, "{0} handler added!", logLocation);
			}
		} catch (IOException ex) {
			logger.log(Level.WARNING, "could not add file handler " + logLocation + " to this logger", ex);
		}
	}
	
	private final static DateFormat df = new SimpleDateFormat("dd.MM.yyyy hh:mm:ss.SSS");

	public static void contextInitialized(ServletContextEvent event) {
		context = event.getServletContext();
		errorLoggers.put(context.getContextPath(), getLogger(CarabiLogging.class, "carabi_errors_log"));
		statisticsLoggers.put(context.getContextPath(), getLogger(CarabiLogging.class, "carabi_statistics_log"));
	}

	public static void contextDestroyed(ServletContextEvent sce) {
		for (FileHandler handler: handlers.values()) {
			for (Logger logger: loggers) {
				logger.removeHandler(handler);
			}
			handler.close();
		}
		loggers.clear();
		handlers.clear();
	}

	private final static class CarabiFormatter extends Formatter {
		@Override
		public String format(LogRecord record) {
			StringBuilder builder = new StringBuilder(1000);
			builder.append(df.format(new Date(record.getMillis()))).append(" - ");
			builder.append("[").append(record.getSourceClassName()).append(".");
			builder.append(record.getSourceMethodName()).append("] - ");
			builder.append("[").append(record.getLevel()).append("] - ");
			builder.append(formatMessage(record));
			Throwable thrown = record.getThrown();
			if (thrown != null) {
				builder.append("\n\n");
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				record.getThrown().printStackTrace(pw);
				builder.append(sw.toString());
			}
			builder.append("\n");
			return builder.toString();
		}
		@Override
		public String getHead(Handler h) {
			return super.getHead(h);
		}
		@Override
		public String getTail(Handler h) {
			return super.getTail(h);
		}
	}
	
	/**
	 * Создание пользовательского журнала в Carabi.
	 * Следует вызывать эту функцию при авторизации пользователя (создании токена или чтения из базы).
	 * Она создаёт запись в таблице в Oracle о текущей сессии, к которой позже
	 * будут привязываться другие.
	 * @param logon Сессия зашедшего пользователя
	 * @param connection подключение к Oracle, в котором открывается лог. скорее всего, оно будет из logon, но при его получении внутри данной функции возникнет рекурсия.
	 * @return ID созданного лога
	 */
	public static int openUserLog(UserLogon logon, Connection connection) throws SQLException, CarabiException, NamingException {
		createLoggersIfNotExists(logon);
		return openDatabaseLog(connection, logon);
	}

	private static int openDatabaseLog(Connection connection, UserLogon logon) throws NamingException, SQLException {
		return 0;//cutted
	}
	
	private static final Map<String, Map<String, Logger>> personalLoggers = new ConcurrentHashMap<>();
	private static void createLoggersIfNotExists(UserLogon logon) {
		if (!personalLoggers.containsKey(logon.getToken())) {
			Map<String, Logger> loggersPerContext = new ConcurrentHashMap<>();
			personalLoggers.put(logon.getToken(), loggersPerContext);
		}
	}
	
	public static void log(UserLogon logon, Object context, String message, String details) {
		log(logon, context.getClass().getName(), message, details, Level.FINE);
	}
	public static void log(UserLogon logon, Class context, String message, String details) {
		log(logon, context.getName(), message, details, Level.FINE);
	}
	public static void log(UserLogon logon, String context, String message, String details) {
		log(logon, context, message, details, Level.FINE);
	}
	public static void warning(UserLogon logon, Object context, String message, String details) {
		log(logon, context.getClass().getName(), message, details, Level.WARNING);
	}
	public static void warning(UserLogon logon, Class context, String message, String details) {
		log(logon, context.getName(), message, details, Level.WARNING);
	}
	public static void warning(UserLogon logon, String context, String message, String details) {
		log(logon, context, message, details, Level.WARNING);
	}
	public static void severe(UserLogon logon, Object context, String message, String details) {
		log(logon, context.getClass().getName(), message, details, Level.SEVERE);
	}
	public static void severe(UserLogon logon, Class context, String message, String details) {
		log(logon, context.getName(), message, details, Level.SEVERE);
	}
	public static void severe(UserLogon logon, String context, String message, String details) {
		log(logon, context, message, details, Level.SEVERE);
	}
	public static void log(UserLogon logon, String context, String message, String details, Level level) {
		if (Settings.WRITE_CARABI_LOGS) {
			createLoggersIfNotExists(logon);
			Logger logger;
			logger = personalLoggers.get(logon.getToken()).get(context);
			if (logger == null) {
				logger = getLogger(context);
				addHandler(logger, getLogonLogLocation(logon));
				personalLoggers.get(logon.getToken()).put(context, logger);
			}
			logger.log(level, "{0} | {1}", new Object[]{message, details});
		}
		
		logToDatabase(logon, message, details);
	}

	private static void logToDatabase(UserLogon logon, String message, String details) {
		//cutted
	}
	
	private static String getLogonLogLocation(UserLogon logon) {
		return Settings.CARABI_LOGS_LOCATION + "/" + logon.userLogin() + "/" + logon.getToken();
	}
	
	public static void closeUserLog(UserLogon logon, Connection connection) throws SQLException {
		personalLoggers.remove(logon.getToken());
		closeDatabaseLog(connection, logon);
	}

	private static void closeDatabaseLog(Connection connection, UserLogon logon) throws SQLException {
		//cutted
	}
	
	private static final Formatter formatter = new CarabiFormatter();
	/**
	 * Краткая запись об ошибке.
	 * Запись производится в отдельный файл и, при возмости, в базу
	 * @param message Запись в лог
	 * @param parameters Параметры записи
	 * @param connection Подключение к базе, в которую писать
	 * @param writeToBase Писать ли в базу
	 * @param level Уровень сообщения
	 * @param e Исключение, если есть
	 */
	public static void logError(String message, Object[] parameters, Connection connection, boolean writeToBase, Level level, Throwable e) {
		Logger errorLogger = getErrorLogger();
		Logger statisticsLogger = getStatisticsLogger();
		LogRecord record = new LogRecord(level, message);
		if (parameters != null) {
			record.setParameters(parameters);
		}
		if (writeToBase) {
			errorToBase(connection, e, record, errorLogger, statisticsLogger);
		}
//		record.set
		if (e != null ) {
			record.setMessage(message + "\n\n" + e.getMessage());
			statisticsLogger.log(record);
			record.setMessage(message);
			record.setThrown(e);
			errorLogger.log(record);
		} else {
			statisticsLogger.log(record);
			errorLogger.log(record);
		}
	}

	private static void errorToBase(Connection connection, Throwable e, LogRecord record, Logger errorLogger, Logger statisticsLogger) {
		try {
			if (connection != null && !connection.isClosed() && connection.isValid(10)) {
				String sql = " begin write_error_log(?); end;";
				try (CallableStatement statement = connection.prepareCall(sql)) {
					if (e != null) {
						statement.setString(1, "Glassfish server:\n" + formatter.formatMessage(record) + "\nException:" + e.getMessage());
					} else {
						statement.setString(1, "Glassfish server:\n" + formatter.formatMessage(record));
					}
					statement.execute();
				}
			} else {
				errorLogger.log(Level.SEVERE, "Connection invalid, can not log to base");
				statisticsLogger.log(Level.SEVERE, "Connection invalid, can not log to base");
			}
		} catch (SQLException ex) {
			errorLogger.log(Level.SEVERE, "error on logging to base", ex);
			statisticsLogger.log(Level.SEVERE, "error on logging to base");
		}
	}
	
	public static Logger getErrorLogger() {
		return errorLoggers.get(context.getContextPath());
	}
	
	public static Logger getStatisticsLogger() {
		return statisticsLoggers.get(context.getContextPath());
	}
}
