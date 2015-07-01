package ru.carabi.server;

import java.io.Serializable;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import ru.carabi.server.kernel.ConnectionsGateBean;
import ru.carabi.server.kernel.UsersControllerBean;
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
	private static final long serialVersionUID = 2L;
	private static final Logger logger = Logger.getLogger(UserLogon.class.getName());
	
	@Id
	private String token;
	
	@Column(name="ORACLE_USER_ID")
	private int id;//ID пользователя в Carabi

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
	private final Map<Long, Connection> connections = new ConcurrentHashMap<>();
	
	/**
	 * Статусы посключений (занято / не занято).
	 */
	@Transient
	private final Map<Long, Boolean> connectionsFree = new ConcurrentHashMap<>();
	
	@Transient
	private final Map<Long, Date> connectionsLastActive = new ConcurrentHashMap<>();
	/*
	 Алгоритм:
	 1 при вызове getConnection() из внешнего кода возвращается подключение из коллекции
	   connections, имеющее статус connectionsFree == true;
	 2 если нет свободных соединений, создать новое через connectionsGate
	 3 
	*/
	
	@Transient
	private ConnectionsGateBean connectionsGate;
	
	@Transient
	private UsersControllerBean usersController;
	
	@Column(name="IP_ADDR_GREY")
	private String greyIpAddr;
	
	@Column(name="IP_ADDR_WHITE")
	private String whiteIpAddr;
	
	@Column(name="SERVER_CONTEXT")
	private String serverContext;
	
	@Transient
	private int oracleSID = -1;//ID сессии в Oracle -- для журналирования и контроля
	
	@Transient
	private int carabiLogID = -1;//ID Carabi-журнала в Oracle
	
	@Transient
	private Date logonDate = new Date(); // дата и время создания сессии
	
	/**
	 * Возврашает ID Carabi-пользователя
	 * @return ID пользователя в Carabi (в базе Oracle)
	 */
	public int getId() {
		return id;
	}
	
	public void setId(int id) {
		this.id = id;
	}
	
	public CarabiUser getUser() {
		return user;
	}
	
	public void setUser(CarabiUser user) {
		this.user = user;
	}
	
	public String getPasswordCipher() {
		return user.getPassword();
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
	 * @return connection
	 */
	public synchronized Connection getConnection() throws CarabiException, SQLException {
		Connection connection = null;
		Long key = null;
		for (Entry<Long, Boolean> isConnectionFree: connectionsFree.entrySet()) {
			if (isConnectionFree.getValue()) { //Подключение свободно
				key = isConnectionFree.getKey();
				connection = connections.get(key);
				break;
			}
		}
		if (connection != null) { //Свободное подключение найдено
			connectionsFree.put(key, false);
			connectionsLastActive.put(key, new Date());
			return connection;
		}
		try {
			//Свободное подключение не найдено
			connection = connectionsGate.connectToSchema(schema);
			authorise(connection);
			key = Math.round(Math.random() * Long.MAX_VALUE);
			connectionsFree.put(key, false);
			connectionsLastActive.put(key, new Date());
			return connection;
		} catch (NamingException ex) {
			throw new CarabiException(ex);
		}
	}
	
	/**
	 * 
	 */
	public void monitorConnections() {
		long timestamp = new Date().getTime();
		for (Entry<Long, Boolean> isConnectionFree: connectionsFree.entrySet()) {
			Long key = isConnectionFree.getKey();
			try {
				if (! isConnectionFree.getValue()) { //Подключение может быть занятым
					Connection connection = connections.get(key);
					//получим SID этого подключения и получим обратную связь от Oracle,
					//освободилось ли оно.
					PreparedStatement getSidStatement = connection.prepareStatement("select SID from dual");
					ResultSet sidSet = getSidStatement.executeQuery();
					sidSet.next();
					int sid = sidSet.getInt(1);
					PreparedStatement getStatusStatement = masterConnection.prepareStatement("select status from v$session where sid = ?");
					getStatusStatement.setInt(1, sid);
					ResultSet statusSet = getStatusStatement.executeQuery();
					String status = statusSet.getString(1);
					if ("ACTIVE".equals(status)) {//Подключение дейчтвительно занято
						continue;
					} else {//Подключение освободилось
						connectionsFree.put(key, true);
						connectionsLastActive.put(key, new Date());
					}
				} else { //подключение свободно. Если свободно уже давно -- закрываем
					long lastActiveTimestamp = connectionsLastActive.get(key).getTime();
					if (timestamp - lastActiveTimestamp > Settings.SESSION_LIFETIME * 1000) {
						connections.get(key).close();
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
		checkConnection(masterConnection);
		return masterConnection;
	}
	
	public void setMasterConnection(Connection connection) {
		this.masterConnection = connection;
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
	 * Запрос к БД Oracle, необходимый, чтобы PL/SQL-функции Carabi
	 * (или иной нижележащей БД) принимали сессию авторизованного пользователя
	 * @param connection
	 * @throws SQLException 
	 */
	private void authorise(Connection connection) throws SQLException {
		//cutted
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
		return usersController.userHavePermission(this, permission);
	}
	
	public void closeAllConnections() throws SQLException {
		if (masterConnection != null) {
			try {
				setSessionInfo(masterConnection, "", "__freeInPool/SOAP_SERVER");
				if (isRequireSession()) {
					CarabiLogging.closeUserLog(this, Utls.unwrapOracleConnection(masterConnection));
				}
			} catch (SQLException e) {
				logger.log(Level.SEVERE, "error on closing", e);
			} finally {
				masterConnection.close();
				masterConnection = null;
				if (isRequireSession()) {
					oracleSID = -1;
				}
			}
		}
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(super.toString());
		sb.append("[userId=");
		sb.append(getId());
		sb.append(", login=");
		sb.append(userLogin());
		sb.append(", passwordCipher=");
		sb.append(getPasswordCipher());
		sb.append(", display=");
		sb.append(getDisplay());
		sb.append(", requireSession=");
		sb.append(isRequireSession());
		sb.append(", schema=");
		sb.append(getSchema());
		sb.append("]");
		return sb.toString();
	}

	
	private boolean checkConnection(Connection connection) {
		try {
		//	logger.log(Level.INFO, "checkConnection: connection.isClosed():{0}, connection.isValid(10): {1}, checkCarabiLog(): {2}", new Object[]{connection.isClosed(), connection.isValid(10), checkCarabiLog()} );
			boolean ok = connection != null && !connection.isClosed() && connection.isValid(10);
			if (connection == masterConnection) {
				ok = ok && checkCarabiLog();
			}
			if (ok) {
				int currentUserID = selectUserID();
				int currentOracleSID = selectOracleSID();
				if (currentUserID != id || currentOracleSID != oracleSID) {
					logger.log(Level.WARNING, "different id: current in oracle: {0}, in java: {1}", new Object[]{currentUserID, id});
					authorise(connection);
				}
				return true;
			} else {
				if (connection != null) try {
					connection.close();
				} catch(Exception e) {
					logger.log(Level.WARNING, "Error on closing invalid connection", e);
				}
				
				connection = connectionsGate.connectToSchema(schema);
				logger.fine("got new connection");
				authorise(connection);
				logger.fine("new connection auth");
				return checkCarabiLog();
			}
		} catch (CarabiException | NamingException | SQLException ex) {
			Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Error, try to reconnect", ex);
			try {
				if (connection != null) {
					connection.close();
				}
				connection = connectionsGate.connectToSchema(schema);
				authorise(connection);
				return checkCarabiLog();
			} catch (CarabiException | NamingException | SQLException ex1) {
				Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex1);
			}
		}
		return false;
	}
	
	public void openCarabiLog() {
		try {
			oracleSID = selectOracleSID();
			carabiLogID = CarabiLogging.openUserLog(this, masterConnection);
			authorise(masterConnection);
		} catch (SQLException ex) {
			Logger.getLogger(UsersControllerBean.class.getName()).log(Level.WARNING, "could not create journal at first time", ex);
		} catch (CarabiException | NamingException ex) {
			Logger.getLogger(UserLogon.class.getName()).log(Level.SEVERE, null, ex);
		}
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
					CarabiLogging.closeUserLog(this, masterConnection);
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
				"APPL_USER.NOW_EMPLOYEE_ID := -1;\n" +
				"dbms_application_info.set_module(:MODULE_NAME,:ACTION_NAME);\n"+
				"dbms_application_info.set_client_info(:USER_NAME);\n"+
				"end;";
		try (CallableStatement statement = connection.prepareCall(sql)) {
			statement.setString("MODULE_NAME", "SOAP_SERVER");
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
		oracleSID = original.oracleSID;
		carabiLogID = original.carabiLogID;
		logonDate = original.logonDate;
	}
}
