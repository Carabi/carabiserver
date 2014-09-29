package ru.carabi.server.kernel;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import javax.ejb.EJB;
import javax.ejb.Remove;
import javax.ejb.Stateful;
import javax.naming.NamingException;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.soap.SoapUserInfo;

@Stateful
public class AuthorizeBean {
	private Connection connection;
	private ConnectionSchema schema;
	@EJB
	private UsersControllerBean usersController;
	@EJB
	private ConnectionsGateBean connectionsGate;
	private HashMap<String, ?> userInfo = null;
	private CarabiUser currentUser;
	private UserLogon userLogon;

	public Connection getConnection() {
		return connection;
	}

	public ConnectionSchema getSchema() {
		return schema;
	}
	
	
	/**
	 * Поиск пользователя в Oracle.
	 * Сохранение данных о нём, которые не хранятся в Derby/
	 * @param user пользователь
	 * @return найден ли пользователь
	 */
	public boolean searchCurrentUser() throws SQLException {
		userInfo = new HashMap<String, Object>();
		return true;
	}
	
	/**
	 * Поиск пользователя в Oracle, сохранение ID.
	 * (Аналог searchUser(String login) без лишних полей.)
	 * @param login Имя пользователя
	 * @return найден ли пользователь
	 */
	public long getUserID(String login) throws SQLException {
		String sql = "select ct_item_id from contacts where login = ?";
		PreparedStatement statement = connection.prepareStatement(sql);
		statement.setString(1, login);
		ResultSet resultSet = statement.executeQuery();
		if (resultSet.next()) {
			userInfo = Utls.fetchRow(resultSet);
		}
		statement.close();
		if (userInfo != null) {
			return ((BigDecimal)userInfo.get("CT_ITEM_ID")).longValue();
		} else {
			return -1;
		}
	}
	
	/**
	 * Подключение к БД Oracle с выбором схемы по ID или псевдониму
	 * @param schemaID ID схемы (если -1 -- выбираем по имени или основную)
	 * @param schemaName Псевдоним схемы
	 * @param login Логин пользователя, для получения его схему по умолчанию
	 */
	public void connectToDatabase(int schemaID, String schemaName, CarabiUser user) 
			throws CarabiException, NamingException, SQLException 
	{
		// Проверка входных параметров
		if (schemaID < 0 && schemaName == null && user == null || user.getLogin() == null || user.getLogin().equals("")) {
			throw new CarabiException("Внутреняя ошибка. Не заданы ни ИД схемы для подключения к БД, "
					+ "ни её название, ни логин пользователя.");
		}
		
		if (schemaID >= 0 && null != schemaName) {
			throw new CarabiException("Неоднозначные параметры подключения к БД: не должно быть задано и schemaID, и "
					+ "schemaName одновременно.");
		}
		currentUser = user;
		
		// Получение схемы
		schema = connectionsGate.getDedicatedSchema(schemaID, schemaName, user.getLogin());
		
		// Соединение по схеме с ораклом
		connection = connectionsGate.connectToSchema(schema);
	}
	
	public void setCurrentUser(CarabiUser currentUser) {
		this.currentUser = currentUser;
	}
	/**
	 * Закрытие подключения к Oracle, если авторизация не удалась.
	 * Иначе -- подключение сохранится в пользователе.
	 * @throws SQLException 
	 */
	public void closeConnection() throws SQLException {
		if (connection != null) {
			connection.close();
		}
	}
	
	/**
	 * Создание пользователя из выбранных сведений.
	 * Созданный объект и возвращается, и сохраняется в beans-е для дальнейших действий
	 * @return Авторизовавшийся пользователь
	 */
	public UserLogon createUserLogon() {
		userLogon = new UserLogon();
		userLogon.setId(-1);
		userLogon.setUser(currentUser);
		userLogon.setPasswordCipher("==");
		userLogon.setDisplay(currentUser.getLastname());
		userLogon.setSchema(schema);
		userLogon.setConnection(connection);
		userLogon.setAppServer(Settings.getCurrentServer());
		userLogon.updateLastActive();
		return userLogon;
	}
	
	/**
	 * Авторизация пользователя в ядре.
	 * Генерация токена, запись сведений о пользователе в служебную БД,
	 * сохранение объекта пользователя в ядре. Закрытие сессии, если она не требуется.
	 * @param requireSession
	 * @return String Токен авторизации
	 */
	public String authorizeUser (boolean requireSession) throws SQLException {
		userLogon.setRequireSession(requireSession);
		userLogon = usersController.addUser(userLogon);
		if (!requireSession) {
			userLogon.closeAllConnections();
		}
		return userLogon.getToken();
	}
	
	/**
	 * Подготовка информации о пользователе к отправке
	 * @return SoapUserInfo
	 */
	public SoapUserInfo createSoapUserInfo() {
		SoapUserInfo soapUserInfo = new SoapUserInfo();
		soapUserInfo.ct_item_id = ((BigDecimal)userInfo.get("CT_ITEM_ID")).intValue();
		soapUserInfo.display = (String)userInfo.get("DISPLAY");
		soapUserInfo.owner = ((BigDecimal)userInfo.get("OWNER")).intValue();
		soapUserInfo.owner_fullname = (String)userInfo.get("OWNER_FULLNAME");
		soapUserInfo.parent = ((BigDecimal)userInfo.get("PARENT")).intValue();
		soapUserInfo.parent_display = (String)userInfo.get("PARENT_DISPLAY");
		soapUserInfo.role = ((BigDecimal)userInfo.get("ROLE")).intValue();
		soapUserInfo.role_descr = (String)userInfo.get("ROLE_DESCR");
		soapUserInfo.userrole_id = ((BigDecimal)userInfo.get("ROLE_ID")).intValue();
		soapUserInfo.license_to = (String)userInfo.get("ORG");
		return soapUserInfo;
	}
	
	/**
	 * Remove-метод (очистка EJB-объекта при удалении из бизнес-клиента)
	 */
	@Remove
	public void remove() {
		userLogon = null;
		userInfo = null;
		connection = null;
	}
}
