package ru.carabi.server.kernel;

import java.sql.Connection;
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
	private UserLogon logon;
	private final UsersFormatter usersFormatter = new EmptyUsersFormatter();
	
	public Connection getConnection() {
		return connection;
	}
	
	public ConnectionSchema getSchema() {
		return schema;
	}
	
	/**
	 * Поиск пользователя в текущей неядровой БД.
	 * Сохранение данных о нём, которые не хранятся в ядровой базе
	 * @return найден ли пользователь
	 */
	public boolean searchCurrentUser() throws SQLException {
		userInfo = usersFormatter.getDetailedUserInfo(connection, currentUser.getLogin());
		return userInfo != null;
	}
	
	/**
	 * Поиск пользователя в текущей неядровой БД, сохранение ID.
	 * (Аналог searchCurrentUser() без лишних полей.)
	 * @param login Имя пользователя
	 * @return ID пользователя
	 */
	public long getUserID(String login) throws SQLException {
		return usersFormatter.getUserID(connection, login);
	}
	
	/**
	 * Подключение к неядровой БД с выбором схемы по ID или псевдониму
	 * @param schemaID ID схемы (если -1 -- выбираем по имени или основную)
	 * @param schemaName Псевдоним схемы
	 */
	public void connectToDatabase(int schemaID, String schemaName) throws CarabiException, NamingException, SQLException {
		// Проверка входных параметров
		if (schemaID < 0 && schemaName == null && currentUser == null || currentUser.getLogin() == null || currentUser.getLogin().equals("")) {
			throw new CarabiException("Внутреняя ошибка. Не заданы ни ИД схемы для подключения к БД, "
					+ "ни её название, ни логин пользователя.");
		}
		
		if (schemaID >= 0 && null != schemaName) {
			throw new CarabiException("Неоднозначные параметры подключения к БД: не должно быть задано и schemaID, и "
					+ "schemaName одновременно.");
		}
		
		// Получение схемы
		schema = connectionsGate.getDedicatedSchema(schemaID, schemaName, currentUser.getLogin());
		
		// Соединение по схеме с ораклом
		connection = connectionsGate.connectToSchema(schema);
	}
	
	public void setCurrentUser(CarabiUser currentUser) {
		this.currentUser = currentUser;
	}
	
	/**
	 * Закрытие подключения к Oracle, если оно не требуется.
	 * Иначе &mdash; подключение сохранится в сессии.
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
		logon = new UserLogon();
		logon.setId(usersFormatter.getUserID(userInfo));
		logon.setUser(currentUser);
		logon.setDisplay(usersFormatter.getUserDisplayString(currentUser, userInfo));
		logon.setSchema(schema);
		logon.setMasterConnection(connection);
		logon.setAppServer(Settings.getCurrentServer());
		return logon;
	}
	
	/**
	 * Авторизация пользователя в ядре.
	 * Генерация токена, запись сведений о пользователе в служебную БД,
	 * сохранение объекта пользователя в ядре. Закрытие сессии, если она не требуется.
	 * @param requireSession
	 * @return String Токен авторизации
	 */
	public String authorizeUser (boolean requireSession) throws SQLException {
		logon.setRequireSession(requireSession);
		logon = usersController.addUser(logon);
		if (!requireSession) {
			logon.closeAllConnections();
		}
		return logon.getToken();
	}
	
	/**
	 * Подготовка информации о пользователе к отправке
	 * @return SoapUserInfo
	 */
	public SoapUserInfo createSoapUserInfo() {
		return usersFormatter.createSoapUserInfo(userInfo);
	}
	
	/**
	 * Remove-метод (очистка EJB-объекта при удалении из бизнес-клиента)
	 */
	@Remove
	public void remove() {
		logon = null;
		userInfo = null;
		connection = null;
	}
}
