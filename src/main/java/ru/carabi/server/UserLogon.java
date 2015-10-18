package ru.carabi.server;

import java.io.Serializable;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.NamingException;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.Transient;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Permission;
import ru.carabi.server.kernel.AuthorizeSecondary;
import ru.carabi.server.kernel.AuthorizeSecondaryAbstract;
import ru.carabi.server.kernel.ConnectionsGateBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.oracle.CursorFetcherBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Сессия пользователя Carabi.
 * Объект содержит данные о пользователе, сессии Oracle, авторизационный токен.
 * Может сохраняться в {@link UsersControllerBean} в течение коротких промежутков времени и в базе
 * данных в течение длительных.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@NamedQueries({
	@NamedQuery(name="deleteOldLogons",
		query="delete from UserLogon U where U.permanent != 1 and (U.lastActive < :long_ago OR U.lastActive is null)"),
	@NamedQuery(name="getOnlineUsers",
		query="select U.user.login from UserLogon U where U.permanent != 1 and U.lastActive >= :recently")
})
@Table(name="USER_LOGON")
public class UserLogon implements Serializable, AutoCloseable {
	private static final long serialVersionUID = 3L;
	private static final Logger logger = Logger.getLogger(UserLogon.class.getName());
	
	@Id
	private String token;
	
	@Column(name="ORACLE_USER_ID")
	private long externalID;//ID пользователя в Carabi

	@ManyToOne
	@JoinColumn(name="USER_ID")
	private CarabiUser user;
	
	private String display;
	
	@ManyToOne
	@JoinColumn(name="APPSERVER_ID")
	private CarabiAppServer appServer;//прикладной сервер Carabi, через который работает пользователь
	
	@ManyToOne
	@JoinColumn(name="SCHEMA_ID")
	private ConnectionSchema schema;//схема Carabi, с которой работает пользователь
	
	@Column(name="REQUIRESESSION")
	private int requireSessionInt;
	
	@Temporal(javax.persistence.TemporalType.TIMESTAMP)
	private Date lastActive; //Время, когда пользователь последний раз заходил
	                         //на сервис (давно не заходившие удаляются)
	
	/**
	 * Основное подключение к БД, с которой работает пользователь
	 */
	@Transient
	private Connection masterConnection;
	
	/**
	 * Коллекция подключений, выдаваемых внешним методам.
	 */
	@Transient
	private Map<Integer, Connection> connections = new ConcurrentHashMap<>();
	
	/**
	 * Статусы посключений (занято / не занято).
	 */
	@Transient
	private Map<Integer, Boolean> connectionsFree = new ConcurrentHashMap<>();
	
	/**
	 * Последнее использование каждого подключения
	 */
	@Transient
	private Map<Integer, Date> connectionsLastActive = new ConcurrentHashMap<>();
	/*
	 Алгоритм:
	 1 при вызове getConnection() из внешнего кода возвращается подключение из коллекции
	   connections, имеющее статус connectionsFree == true;
	 2 если нет свободных соединений, создать новое через connectionsGate;
	 3 UsersControllerBean каждую минуту вызывает monitorConnections.
	   3.1 Для сессий, помеченных занятыми, выполняется запрос в Oracle, проверяющий, освободилось ли оно;
	   3.2 давно свободные сессии закрываются, если нет открытых на них курсоров;
	 4 пользователь может освободить сессию сам, вызвав freeConnection.
	   Она сразу помечается свободной в UserLogon, чтобы не открывались лишние,
	   в Oracle будет закрыта спустя Settings.SESSION_LIFETIME
	*/
	
	@Transient
	private ConnectionsGateBean connectionsGate;
	
	@Transient
	private UsersControllerBean usersController;
	
	/**
	 * IP-адрес клиента, передаваемый клиентом
	 */
	@Column(name="IP_ADDR_GREY")
	private String greyIpAddr;
	
	/**
	 * IP-адрес клиента, определяемый сервером
	 */
	@Column(name="IP_ADDR_WHITE")
	private String whiteIpAddr;
	
	/**
	 * Адрес сервера, к которому подключились
	 */
	@Column(name="SERVER_CONTEXT")
	private String serverContext;
	
	/**
	 * Название подключившейся программы
	 */
	@Column(name="USER_AGENT")
	private String userAgent;
	
	@Transient
	private int oracleSID = -1;//ID сессии в Oracle -- для журналирования и контроля
	
	@Transient
	private int carabiLogID = -1;//ID Carabi-журнала в Oracle
	
	@Transient
	private Date logonDate = new Date(); // дата и время создания сессии
	
	@Transient
	private Collection<Permission> permissions;
	
	@Transient
	private Map<String, Boolean> userHavePermission = new ConcurrentHashMap<>();
	
	@Transient
	private AuthorizeSecondary authorizeSecondary = new AuthorizeSecondaryAbstract();
	
	/**
	 * Возврашает ID пользователя в текущей неядровой БД
	 * @return ID пользователя в текущей неядровой БД Oracle
	 */
	public long getExternalId() {
		return externalID;
	}
	
	public void setExternalId(long id) {
		this.externalID = id;
	}
	
	public CarabiUser getUser() {
		return user;
	}
	
	public void setUser(CarabiUser user) {
		this.user = user;
	}
	
	public String getDisplay() {
		return display;
	}
	
	public void setDisplay(String display) {
		this.display = display;
	}
	
	public String getToken() {
		return token;
	}
	
	public void setToken(String token) {
		this.token = token;
	}
	
	@Column(name="PERMANENT")
	private int permanent;
	
	public int getPermanent() {
		return permanent;
	}
	
	public void setPermanent(int permanent) {
		this.permanent = permanent;
	}
	
	public boolean isPermanent() {
		return permanent > 0;
	}
	
	public boolean isRequireSession() {
		return getRequireSessionInt() != 0;
	}
	
	public void setRequireSession(boolean requireSession) {
		if (!requireSession)
			setRequireSessionInt(0);
		else
			setRequireSessionInt(1);
	}
	
	// do not call directly. JPA only method. use this.isRequireSession.
	public int getRequireSessionInt() {
		return requireSessionInt;
	}
	
	// do not call directly. JPA only method. use this.setRequireSession.
	public void setRequireSessionInt(int requireSession) {
		this.requireSessionInt = requireSession;
	}
	
	/**
	 * Получение рабочего подключения к Oracle.
	 * Создание центрального подключеия, если оно не создано.
	 * @return connection
	 */
	public synchronized Connection getConnection() throws CarabiException, SQLException {
		checkMasterConnection();
		Connection connection = null;
		int key = 0;
		for (Entry<Integer, Boolean> isConnectionFree: connectionsFree.entrySet()) {
			if (isConnectionFree.getValue()) { //Подключение свободно
				key = isConnectionFree.getKey();
				connection = connections.get(key);
				break;
			}
		}
		if (connection != null) { //Свободное подключение найдено
			connectionsFree.put(key, false);
			connectionsLastActive.put(key, new Date());
			return checkConnection(connection, true);
		}
		try {
			//Свободное подключение не найдено
			connection = connectionsGate.connectToSchema(schema);
			authorize(connection, true);
			key = getConnectionKey(connection);
			connections.put(key, connection);
			connectionsFree.put(key, false);
			connectionsLastActive.put(key, new Date());
			return connection;
		} catch (NamingException ex) {
			throw new CarabiException(ex);
		}
	}
	
	/**
	 * Освобождение подключения во встроенном пуле.
	 */
	public void freeConnection(Connection connection) {
		int key = getConnectionKey(connection);
		freeConnection(key);
	}
	
	public void freeConnection(int connectionKey) {
		if (connections.get(connectionKey) != null) {
			connectionsFree.put(connectionKey, true);
			lastActive = new Date();
			connectionsLastActive.put(connectionKey,lastActive);
		}
	}
	
	/**
	 * Проверка встроенного пула подключений.
	 * Заняты ли они, закрытие давно не занятых. Должно вызываться по таймеру
	 * из UsersControllerBean
	 * @param cursorFetcher ссылка на модуль прокрутки для доп. проверки занятости подключений
	 */
	public void monitorConnections(CursorFetcherBean cursorFetcher) {
		long timestamp = new Date().getTime();
		for (Entry<Integer, Boolean> isConnectionFree: connectionsFree.entrySet()) {
			int key = isConnectionFree.getKey();
			Connection connection = connections.get(key);
			try {
				if (! isConnectionFree.getValue()) { //Подключение может быть занятым.
					//Получим SID этого подключения и получим обратную связь от Oracle,
					//освободилось ли оно.
					PreparedStatement getSidStatement = connection.prepareStatement("select SID from dual");
					ResultSet sidSet = getSidStatement.executeQuery();
					sidSet.next();
					int sid = sidSet.getInt(1);
					PreparedStatement getStatusStatement = masterConnection.prepareStatement("select status from v$session where sid = ?");
					getStatusStatement.setInt(1, sid);
					ResultSet statusSet = getStatusStatement.executeQuery();
					statusSet.next();
					String status = statusSet.getString(1);
					if ("ACTIVE".equals(status)) {//Подключение дейчтвительно занято
						continue;
					} else {//Подключение освободилось (нет исполняемых в данный момент Statement-ов)
						connectionsFree.put(key, true);
						this.lastActive = new Date();
						connectionsLastActive.put(key, this.lastActive);
					}
				} else if (! cursorFetcher.hasThisConnection(connection)){ //подключение свободно.
					// Если свободно уже давно -- закрываем,
					// убедившись, что не осталось открытых ResultSet-ов (Fetch-ей)
					long lastActiveTimestamp = connectionsLastActive.get(key).getTime();
					if (timestamp - lastActiveTimestamp > Settings.SESSION_LIFETIME * 1000) {
						connection.close();
						connections.remove(key);
						connectionsFree.remove(key);
						connectionsLastActive.remove(key);
					}
				}
			} catch (SQLException ex) {
				Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		
	}
	/**
	 * Получение основного подключения к схеме Oracle. Использовать с осторожностью,
	 * основное подключение используется для ведения журнала и контроля состояния остальных.
	 * @return connection
	 */
	public synchronized Connection getMasterConnection() {
		if (externalID < 0) {//ещё не вошли в неядровую БД
			try (Connection connectionTmp = connectionsGate.connectToSchema(schema)) {
				externalID = authorizeSecondary.getUserID(connectionTmp, userLogin());
			} catch (CarabiException | NamingException | SQLException ex) {
				Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		checkMasterConnection();
		return masterConnection;
	}
	
	public void setConnectionsGate(ConnectionsGateBean cg) {
		this.connectionsGate = cg;
	}
	
	public void setUsersController(UsersControllerBean uc) {
		this.usersController = uc;
	}
	
	public Date getLastActive() {
		return lastActive;
	}
	
	/**
	 * Возвращает текущую схему, с которой работает пользователь
	 * @return ConnectionSchema
	 */
	public ConnectionSchema getSchema() {
		return schema;
	}
	
	public void setSchema(ConnectionSchema schema) {
		this.schema = schema;
	}
	
	public CarabiAppServer getAppServer() {
		return appServer;
	}
	
	public void setAppServer(CarabiAppServer appServer) {
		this.appServer = appServer;
	}
	
	/**
	 * Возвращает серый IP-адрес клиента.
	 * Адрес, указанный клиентом.
	 * Используется только для журналирования.
	 */
	public String getGreyIpAddr() {
		return greyIpAddr;
	}
	
	/**
	 * Устанавливает серый IP-адрес клиента.
	 * Адрес, указанный клиентом.
	 * Используется только для журналирования.
	 */
	public void setGreyIpAddr(String ipAddr) {
		this.greyIpAddr = ipAddr;
	}
	
	/**
	 * Возвращает белый IP-адрес клиента.
	 * Адрес, определённый сервером.
	 * Используется только для журналирования.
	 */
	public String getWhiteIpAddr() {
		return whiteIpAddr;
	}

	/**
	 * Устанавливает белый IP-адрес клиента.
	 * Адрес, определённый сервером.
	 * Используется только для журналирования.
	 */
	public void setWhiteIpAddr(String ipAddr) {
		this.whiteIpAddr = ipAddr;
	}
	
	/**
	 * Возвращает адрес и имя текущего сервера.
	 * Используется только для журналирования.
	 */
	public String getServerContext() {
		return serverContext;
	}
	
	/**
	 * Устанавливает адрес и имя текущего сервера.
	 * Используется только для журналирования.
	 */
	public void setServerContext(String serverContext) {
		this.serverContext = serverContext;
	}
	
	public String getUserAgent() {
		return userAgent;
	}
	
	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}
	
	public int getCarabiLogID() {
		return carabiLogID;
	}
	
	public void setCarabiLogID(int carabiLogID) {
		this.carabiLogID = carabiLogID;
	}
	
	public int getOracleSID() {
		return oracleSID;
	}
	
	public Date getLogonDate() {
		return logonDate;
	}
	
	public void updateLastActive() {
		lastActive = new Date();
		user.setLastActive(new Date());
	}
	
	/**
	 * Авторизация подключения в БД.
	 * Запрос к неядровой БД, необходимый, чтобы PL/SQL-функции
	 * принимали сессию авторизованного пользователя
	 * @param connection
	 * @throws SQLException 
	 */
	private void authorize(Connection connection, boolean openedFromPool) throws SQLException {
		String postfix;
		if (openedFromPool) {
			postfix = "/Pooled";
		} else {
			postfix = "/Master";
		}
		setSessionInfo(connection, "" + carabiLogID, userLogin() + postfix + "/SOAP_SERVER");
		authorizeSecondary.authorizeUser(connection, this);
	}
	
	public Collection<Permission> getPermissions() {
		if (permissions == null) {
			permissions = usersController.getUserPermissions(this);
		}
		return permissions;
	}
	/**
	 * Проверка, имеет ли текущий пользователь указанное право.
	 * Принцип действия:
	 * <ul>
	 * <li>1 Если разрешение или запрет указано непосредственно для пользователя (таблица USER_HAS_PERMISSION) -- вернуть его.
	 * <li>2 Если нет, то искать по группам:
	 * <li>2.1 Если хотя бы в одной группе указано разрешение и ни в одной не указан запрет -- вернуть разрешение
	 * <li>2.2 Если хотя бы в одной группе указан запрет и ни в одной не указано разрешение -- вернуть запрет
	 * <li>3 Иначе (нет данных или противоречие) -- вернуть настройку для права по умолчанию.
	 * </ul>
	 * @param permission
	 * @return 
	 */
	public boolean havePermission(String permission) throws CarabiException {
		if (!userHavePermission.containsKey(permission)) {
			userHavePermission.put(permission, usersController.userHavePermission(this, permission));
		}
		return userHavePermission.get(permission);
	}
	
	public boolean haveAnyPermission(String... permissions) throws CarabiException {
		if (permissions.length == 0) {
			return true;
		}
		for (String permissionSysname: permissions) {
			if (havePermission(permissionSysname)) {
				return true;
			}
		}
		return false;
	}
	
	public boolean haveAllPermissions(String... permissions) throws CarabiException {
		for (String permissionSysname: permissions) {
			if (!havePermission(permissionSysname)) {
				return false;
			}
		}
		return true;
	}
	
	public void closeAllConnections() throws SQLException {
		if (masterConnection != null) {
			try {
				setSessionInfo(masterConnection, "", "__freeInPool/SOAP_SERVER");
				if (externalID >= 0) {
					CarabiLogging.closeUserLog(this);
				}
			} catch (SQLException e) {
				logger.log(Level.SEVERE, "error on closing", e);
			} finally {
				masterConnection.close();
				masterConnection = null;
				oracleSID = -1;
			}
		}
		for (Entry<Integer, Connection> connectionKey: connections.entrySet()) {
			connectionKey.getValue().close();
			int key = connectionKey.getKey();
			connections.remove(key);
			connectionsFree.remove(key);
			connectionsLastActive.remove(key);
		}
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(super.toString());
		sb.append("[userId=");
		sb.append(getExternalId());
		sb.append(", login=");
		sb.append(userLogin());
		sb.append(", display=");
		sb.append(getDisplay());
		sb.append(", requireSession=");
		sb.append(isRequireSession());
		sb.append(", schema=");
		sb.append(getSchema());
		sb.append("]");
		return sb.toString();
	}
	
	/**
	 * Проверка, что подключение к БД "исправно", попытка переподключения при необходимости
	 * @param connection проверяемое подключение
	 * @param openedFromPool подключение создано во встроенном пуле
	 * @return проверяемое подключение или новое, если оригинальное разорвано, или null, если к БД не подключиться
	 */
	private Connection checkConnection(Connection connection, boolean openedFromPool) {
		try {
			boolean ok = connection != null && !connection.isClosed() && connection.isValid(10);
			if (ok) {
				int currentUserID = selectUserID();
				int currentOracleSID = selectOracleSID();
				if (currentUserID != externalID || currentOracleSID != oracleSID) {
					logger.log(Level.WARNING, "different id: current in oracle: {0}, in java: {1}", new Object[]{currentUserID, externalID});
					authorize(connection, openedFromPool);
				}
				return connection;
			} else {
				if (connection != null) try {
					connection.close();
				} catch(Exception e) {
					logger.log(Level.WARNING, "Error on closing invalid connection", e);
				}
				
				Connection newConnection = connectionsGate.connectToSchema(schema);
				logger.fine("got new connection");
				authorize(newConnection, openedFromPool);
				logger.fine("new connection auth");
				return newConnection;
			}
		} catch (CarabiException | NamingException | SQLException ex) {
			Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Error, try to reconnect", ex);
			try {
				if (connection != null) {
					connection.close();
				}
				Connection newConnection = connectionsGate.connectToSchema(schema);
				authorize(newConnection, openedFromPool);
				return newConnection;
			} catch (CarabiException | NamingException | SQLException ex1) {
				Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex1);
			}
		}
		Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, "Connection was invalid, reconnection failed");
		return null;
	}
	
	private void checkMasterConnection() {
		Connection masterConnectionChecked = checkConnection(masterConnection, false);
		if (masterConnectionChecked != masterConnection) {// переподключились
			masterConnection = masterConnectionChecked;
		}
		checkCarabiLog();
	}
	
	/**
	 * Проверка, что журнал в Carabi создан и не переподключалась сессия.
	 * @return true, если старый журнал актуален или новый создан успешно.
	 * false, если подключиться и создать журнал не удалось.
	 */
	private boolean checkCarabiLog() {
		try {
			int currentOracleSID = selectOracleSID();
			logger.log(Level.FINE, "checkCarabiLog: carabiLogID: {0}, currentOracleSID: {1}, oracleSID: {2}", new Object[]{carabiLogID, currentOracleSID, oracleSID});
			if (carabiLogID == -1 || currentOracleSID != oracleSID) {
				if (carabiLogID != -1) {
					CarabiLogging.closeUserLog(this);
				}
				oracleSID = currentOracleSID;
				carabiLogID = CarabiLogging.openUserLog(this, masterConnection);
			}
			return true;
		} catch (SQLException ex) {
			CarabiLogging.logError("could not create journal", null, null, false, Level.SEVERE, ex);
//			Logger.getLogger(UsersControllerBean.class.getName()).log(Level.SEVERE, "could not create journal", ex);
		} catch (CarabiException | NamingException ex) {
			Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex);
		}
		return false;
	}
	
	/**
	 * Установка служебной информации в сессии Oracle.
	 * Указание, что сессия принадлежит конкретному пользователю и данной программе.
	 * @param connection подписываемая сессия
	 * @param actionName информация о действии, передаваемая в Oracle &mdash; сейчас используется ID журнала
	 * @param userName информация о текущем пользователе, передаваемая в Oracle
	 */
	private void setSessionInfo(Connection connection, String actionName, String userName) throws SQLException {
		String sql = "begin \n" +
				"dbms_application_info.set_module(:MODULE_NAME,:ACTION_NAME);\n"+
				"dbms_application_info.set_client_info(:USER_NAME);\n"+
				"end;";
		try (CallableStatement statement = connection.prepareCall(sql)) {
			statement.setString("MODULE_NAME", "SOAP_SERVER/" + userAgent);
			statement.setString("ACTION_NAME", actionName);
			statement.setString("USER_NAME", userName);
			statement.execute();
		}
	}
	
	@Override
	public void close() {
		if (!isRequireSession()) {
			try {
				closeAllConnections();
				logger.finest("Connection closed");
			} catch (SQLException ex) {
				Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}
	/**
	 * Получение ID сессии в Oracle.
	 */
	private int selectOracleSID() throws SQLException, CarabiException {
		String sql = "SELECT SID FROM V$MYSTAT WHERE ROWNUM = 1";
		try (PreparedStatement statement = masterConnection.prepareStatement(sql)) {
			ResultSet sidResultSet = statement.executeQuery();
			if (sidResultSet.next()) {
				return sidResultSet.getInt(1);
			} else {
				throw new CarabiException("could not get SID");
			}
		}
	}
	/**
	 * Получение ID пользователя в Oracle.
	 */
	private int selectUserID() throws SQLException, CarabiException {
		String sql = "SELECT documents.GET_USER_ID from dual";
		try (PreparedStatement statement = masterConnection.prepareStatement(sql)) {
			ResultSet sidResultSet = statement.executeQuery();
			if (sidResultSet.next()) {
				return sidResultSet.getInt(1);
			} else {
				throw new CarabiException("could not get SID");
			}
		}
	}
	
	public String userLogin() {
		return user.getLogin();
	}

	public void copyTrancientFields(UserLogon original) {
		usersController = original.usersController;
		connectionsGate = original.connectionsGate;
		masterConnection = original.masterConnection;
		connections = original.connections;
		connectionsFree = original.connectionsFree;
		connectionsLastActive = original.connectionsLastActive;
		oracleSID = original.oracleSID;
		carabiLogID = original.carabiLogID;
		logonDate = original.logonDate;
	}
	
	/**
	 * Получение идентификатора подключения, открытого через встроенный пул сессии
	 * @param connection
	 * @return 
	 */
	public int getConnectionKey(Connection connection) {
		return connection.hashCode();
	}
	
	/**
	 * Проверка, что текущий пользователь может выполнять некоторое действие.
	 * Для этого он должен иметь указанное право,
	 * иначе кидается исключение.
	 * @param permissionSysname Требуемое право
	 * @throws ru.carabi.server.CarabiException Если право не найдено или отсутствует у пользователя
	 */
	public void assertAllowed(String permissionSysname) throws CarabiException {
		if (!havePermission(permissionSysname)) {
			throw new PermissionException(this, permissionSysname);
		}
	}
	
	/**
	 * Проверка, что текущий пользователь может выполнять некоторое действие.
	 * Для этого он должен иметь хотя бы одно указанное право,
	 * иначе кидается исключение.
	 * @param permissionsSysname Требуемые права
	 * @throws ru.carabi.server.CarabiException Если право не найдено или отсутствует у пользователя
	 */
	public void assertAllowedAny(String[] permissionsSysname) throws CarabiException {
		if (permissionsSysname.length == 0) {
			return;
		}
		for (String permissionSysname: permissionsSysname) {
			if (usersController.userHavePermission(this, permissionSysname)) {
				return;
			}
		}
		throw new PermissionException(this, permissionsSysname[0]);
	}
	
	/**
	 * Проверка, что текущий пользователь может выполнять некоторое действие.
	 * Для этого он должен иметь все указанные права,
	 * иначе кидается исключение.
	 * @param permissionsSysname Требуемые права
	 * @throws ru.carabi.server.CarabiException Если право не найдено или отсутствует у пользователя
	 */
	public void assertAllowedAll(String[] permissionsSysname) throws CarabiException {
		for (String permissionSysname: permissionsSysname) {
			if (!usersController.userHavePermission(this, permissionSysname)) {
				throw new PermissionException(this, permissionSysname);
			}
		}
	}
}
