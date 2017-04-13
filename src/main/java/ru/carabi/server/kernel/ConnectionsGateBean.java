package ru.carabi.server.kernel;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
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
import javax.sql.DataSource;
import oracle.jdbc.pool.OracleDataSource;
import oracle.jdbc.xa.client.OracleXADataSource;
import ru.carabi.server.CarabiException;
import ru.carabi.server.OracleConnectionError;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.Settings;

/**
 * Создание подключений к Oracle.
 * Производится напрямую или с использованием пула EE-сервера. Настройки берутся
 * из объекта {@link ConnectionSchema} в ядровой базе. Может использоваться основная БД
 * пользователя или вабранная им по ID или псевдониму.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Stateless
public class ConnectionsGateBean {
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;//Доступ через JPA -- только для служебной информации в ядровой базе
	
	@EJB ConnectorBean connector;
	static final Logger logger = Logger.getLogger(ConnectionsGateBean.class.getName());
	
	/**
	 * Возвращает указанную в настройках пользователя базу по умолчанию
	 * @param login логин пользователя
	 */
	public ConnectionSchema getDefaultConnectionSchema(String login) throws CarabiException {
		if (null == login) {
			throw new CarabiException("Не задан логин пользователя (login: null).");
		}
		
		TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfo", CarabiUser.class);
		activeUser.setParameter("login", login);
		try {
			CarabiUser user = activeUser.getSingleResult();
			return user.getDefaultSchema();
		} catch (NoResultException e) {
			final String msg = "Не найдено пользователя по логину: "+login+".";
			logger.log(Level.CONFIG, msg);
			throw new CarabiException(msg,e);
		}
	}
	
	/**
	 * Получение подключения к базе Carabi по ID
	 */
	public ConnectionSchema getConnectionSchemaByID(int id) throws CarabiException {
		ConnectionSchema schema = em.find(ConnectionSchema.class, new Integer(id));
		if (schema == null) {
			throw new OracleConnectionError("No schema with id: " + id);
		}
		return schema;
	}
	
	/**
	 * Получение подключения к базе Carabi по псевдониму.
	 * @param sysname псевдоним схемы Carabi
	 * @throws CarabiException если такой схемы нет
	 */
	public ConnectionSchema getConnectionSchemaByAlias(String sysname) throws CarabiException {
		logger.log(Level.FINE, "ru.carabi.server.kernel.getConnectionSchemaByAlias with params: sysname={0}", new Object[]{sysname});
		
		TypedQuery<ConnectionSchema> query = em.createNamedQuery("findSchemaBySysname", ConnectionSchema.class);
		query.setParameter("sysname", sysname);
		try {
			ConnectionSchema schema = query.getSingleResult();
			return schema;
		} catch (NoResultException e) {
			throw new OracleConnectionError("Схема не найдена по имени: "+sysname);
		}
	}
	
	/**
	 * Получение подключения к базе Carabi по JNDI-имени пула
	 */
	private Connection getDatabaseConnectionByJNDI(String jndi) throws CarabiException, NamingException, SQLException {
		Context ctx = new InitialContext();
		DataSource dataSource = (DataSource)ctx.lookup(jndi);
		if (dataSource == null) {
			throw new OracleConnectionError("No such pool: " + jndi);
		}
		Connection connection = null;
		//Пул пожет быть недоступным, при ошибке делаем паузу, пробуем подключиться повторно.
		//Эксперимент показал, что это бесполезно, но для безопасного возврата в
		//SOAP-уровень (после чего подключение может быть успешно перезапрошено оттуда)
		//зачем-то надо обменяться Runtime-Exception-ом
		synchronized(this) {
			for (int trying=0; trying<2 && connection==null; trying++) {
				try {
					logger.fine("try get connection");
					logger.log(Level.FINEST, "StackTrace", new Exception());
					connection = connector.getConnection(jndi, trying>0);
					if (connection == null || connection.isClosed() || !connection.isValid(10)) {
						logger.warning("connection is null");
						this.wait(Settings.RECONNECTION_PAUSE * 1000);
					}
					logger.fine("got connection");
				} catch (Exception ex) {
					logger.log(Level.FINE, null, ex);
					logger.log(Level.FINE, "trying {0}", trying);
					if (trying == 0) {
						try {
							this.wait(Settings.RECONNECTION_PAUSE * 1000);
						} catch (InterruptedException ex1) {
							Logger.getLogger(ConnectionsGateBean.class.getName()).log(Level.SEVERE, null, ex1);
						}
					}
				}
			}
		}
		if (connection != null && !connection.isClosed() && connection.isValid(10)) {
			return connection;
		} else {
			throw new CarabiException("Pool " + jndi + " is unreachable");
		}
	}
	
	/**
	 * Получение JDBC-подключения
	 * @param address
	 * @param login
	 * @param password
	 * @return JDBC-подключение к Oracle 
	 * @throws SQLException
	 * @throws CarabiException 
	 */
	private Connection getDatabaseConnectionByJDBC(String address, String login, String password) throws SQLException, CarabiException {
		OracleDataSource ods;
		Connection connection = null;
		synchronized(this) {
			for (int trying=0; trying<5 && connection==null; trying++) {
				try {
					ods = new OracleXADataSource();
					Locale.setDefault(Locale.US);
					ods.setURL(address);
					ods.setUser(login);
					ods.setPassword(password);
					connection = ods.getConnection();
					if (connection == null || connection.isClosed() || !connection.isValid(10)) {
						CarabiLogging.logError("Got empty connection to {0} on trying {1}", new Object[]{address, new Integer(trying+1)}, null, false, Level.SEVERE, null);
						this.wait(Settings.RECONNECTION_PAUSE * 1000);
					} else if (trying > 0) {
						CarabiLogging.logError("Got valid connection to {0} on trying {1}", new Object[]{address, new Integer(trying+1)}, null, false, Level.SEVERE, null);
					} else {
						logger.fine("got connection");
					}
				} catch (Exception ex) {
					CarabiLogging.logError("Error on connectiong to {0} on trying {1}", new Object[]{address, new Integer(trying+1)}, null, false, Level.SEVERE, ex);
					logger.log(Level.FINE, "trying {0}", trying);
					if (trying == 0) {
						try {
							this.wait(Settings.RECONNECTION_PAUSE * 1000);
						} catch (InterruptedException ex1) {
							Logger.getLogger(ConnectionsGateBean.class.getName()).log(Level.SEVERE, null, ex1);
						}
					}
				}
			}
		}
		if (connection != null && !connection.isClosed() && connection.isValid(10)) {
			return connection;
		} else {
			throw new CarabiException("base " + address + "@" + login + " is unreachable");
		}
	}
	
	/**
	 * Получение схемы по приоритетному заданному параметру.
	 * Если задан номер &mdash; по номеру, если нет &mdash; по названию, если нет &mdash;
	 * основную схему пользователя.
	 * @param schemaID
	 * @param schemaName
	 * @param login
	 * @return
	 * @throws CarabiException
	 */
	public ConnectionSchema getDedicatedSchema (int schemaID, String schemaName, String login) throws CarabiException {
		// Проверка входных параметров
		if (schemaID < 0 && schemaName == null && login == null || login.equals("")) {
			throw new CarabiException("please input not empty schemaID, schemaName or login");
		}
		if (schemaID >= 0) {
			return getConnectionSchemaByID(schemaID);
		} else if (null != schemaName && !schemaName.isEmpty()) {
			return getConnectionSchemaByAlias(schemaName);
		} else {
			return getDefaultConnectionSchema(login);
		}
	}
	
	/**
	 * Подключение к указанной базе Oracle
	 * @param schema данные о требуемой БД
	 * @return подключение (сессия) Oracle
	 * @throws NamingException не найден JNDI-ресурс (только при использовании Java EE-пула на промежуточном уровне, Settings.USE_SYSTEM_POOL==true)
	 * @throws SQLException сбой при подключении
	 * @throws CarabiException база недоступна
	 */
	public Connection connectToSchema(ConnectionSchema schema) throws CarabiException, NamingException, SQLException {
		if (Settings.USE_SYSTEM_POOL) {
			return getDatabaseConnectionByJNDI(schema.getJNDI());
		} else {
			return getDatabaseConnectionByJDBC(schema.getAddress(), schema.getLogin(), schema.getPassword());
		}
	}
	
}
