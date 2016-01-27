package ru.carabi.server.kernel;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.soap.SoapUserInfo;

/**
 * Реализация интерфейса {@link AuthorizeSecondary} для БД Carabi.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public class AuthorizeSecondaryCarabi implements AuthorizeSecondary {
	private static final Logger logger = CarabiLogging.getLogger(AuthorizeSecondaryCarabi.class);
	
	@Override
	public boolean hasUsersList() {
		return true;
	}
	
	@Override
	public boolean supportsAuthorize() {
		return true;
	}
	
	@Override
	public void authorizeUser(Connection connection, UserLogon logon) throws SQLException {
		String sql = "begin\n"+
				"APPL_USER.NOW_EMPLOYEE_ID := -1;\n" +
				"documents.SET_CORE('USE_REGISTER_USER1996');\n" +
				"documents.REGISTER_USER(:USER_ID, 1, 'HOW_USE_REGISTER_USER');\n" +
			"end;";
		try (CallableStatement statement = connection.prepareCall(sql)) {
			statement.setLong("USER_ID", logon.getExternalId());
			statement.execute();
			logger.log(Level.FINE, "token = {0}, assumed login = {1}, act as {2}", new Object[]{logon.getToken(), logon.userLogin(), logon.getExternalId()});
		}
	}
	
	/**
	 * Возвращает ID пользователя Carabi.
	 * @param connection текущее подключение к БД.
	 * @param login логин пользователя
	 * @return ID пользователя в Carabi. -1, если пользователь не найден.
	 */
	@Override
	public long getUserID(Connection connection, String login) {
		String sql = "select ct_item_id from contacts where login = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)){
			statement.setString(1, login);
			ResultSet resultSet = statement.executeQuery();
			Map<String, ?> userInfo = null;
			if (resultSet.next()) {
				userInfo = Utls.fetchRow(resultSet);
			}
			if (userInfo != null) {
				return ((BigDecimal)userInfo.get("CT_ITEM_ID")).longValue();
			} else {
				return -1;
			}
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
			return -1;
		}
	}
	
	/**
	 * Получение подробной выборки о пользователе из Carabi.
	 * @param connection текущее подключение к Oracle.
	 * @param login логин пользователя
	 * @return выборка в виде хеш-таблицы, null, если пользователь не найден
	 */
	@Override
	public Map<String, ?> getDetailedUserInfo(Connection connection, String login) {
		Map<String, ?> userInfo = null;
		String sql = "SELECT "+
"  c.login, "+
"  c.password, "+
"  c.ct_item_id, "+
"  ct.display, "+
"  get_client_contact(c.ct_item_id) AS owner, "+
"  get_department_fullname(get_client_contact(c.ct_item_id)) AS owner_fullname, "+
"  pct.ct_item_id AS parent, "+
"  pct.display AS parent_display, "+
"  get_vocab_code_by_valueid(get_vocab_id('Contact_Role'), user_role) AS role, "+
"  get_vocab_value_by_valueid(get_vocab_id('Contact_Role'), user_role) AS role_descr, "+
"  user_role AS role_id, "+
"  cl.display AS dbname, "+
"  cl.lc_to AS ORG, "+
"  c.winlogon, c.winuser, c.windomain "+
"FROM "+
"  contacts c, "+
"  clients_tree ct, "+
"  clients_tree pct, c_license cl "+
"WHERE "+
"  (UPPER(login) = UPPER(?)) AND "+
"  (ct.ct_item_id = c.ct_item_id) AND "+
"  (pct.ct_item_id = ct.parent)";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, login);
			ResultSet resultSet = statement.executeQuery();
			//Запрос может вернуть 0 или 1 строку
			if (resultSet.next()) {
				userInfo = Utls.fetchRow(resultSet);
			}
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
		}
		return userInfo;
	}
	
	/**
	 * Возвращает объект SoapUserInfo, заполненный данными из метода getDetailedUserInfo.
	 * Реализация для БД Carabi.
	 * @param detailedUserInfo объект, возвращенный методом getDetailedUserInfo
	 * @return объект SoapUserInfo, заполненный данными из параметра detailedUserInfo
	 */
	@Override
	public SoapUserInfo createSoapUserInfo(Map<String, ?> detailedUserInfo) {
		SoapUserInfo soapUserInfo = new SoapUserInfo();
		soapUserInfo.ct_item_id = ((BigDecimal)detailedUserInfo.get("CT_ITEM_ID")).intValue();
		soapUserInfo.display = (String)detailedUserInfo.get("DISPLAY");
		soapUserInfo.owner = ((BigDecimal)detailedUserInfo.get("OWNER")).intValue();
		soapUserInfo.owner_fullname = (String)detailedUserInfo.get("OWNER_FULLNAME");
		soapUserInfo.parent = ((BigDecimal)detailedUserInfo.get("PARENT")).intValue();
		soapUserInfo.parent_display = (String)detailedUserInfo.get("PARENT_DISPLAY");
		soapUserInfo.role = ((BigDecimal)detailedUserInfo.get("ROLE")).intValue();
		soapUserInfo.role_descr = (String)detailedUserInfo.get("ROLE_DESCR");
		soapUserInfo.userrole_id = ((BigDecimal)detailedUserInfo.get("ROLE_ID")).intValue();
		soapUserInfo.license_to = (String)detailedUserInfo.get("ORG");
		return soapUserInfo;
	}
	
	/**
	 * Возвращает ID пользователя, имевшийся в выборке.
	 * Для БД Carabi -- CT_ITEM_ID
	 * @param detailedUserInfo объект, возвращенный методом getDetailedUserInfo
	 * @return ID пользователя, имевшийся в выборке.
	 */
	@Override
	public long getSelectedUserID(Map<String, ?> detailedUserInfo) {
		return ((BigDecimal)detailedUserInfo.get("CT_ITEM_ID")).longValue();
	}
	
	@Override
	public String getUserDisplayString(CarabiUser user, Map<String, ?> userInfo) {
		String display = (String)userInfo.get("DISPLAY");
		if (StringUtils.isEmpty(display)) {
			return user.getFirstname() + " " + user.getMiddlename() + " " + user.getLastname();
		} else {
			return display;
		}
	}

	@Override
	public long getCurrentUserId(Connection connection) throws SQLException, CarabiException {
		String sql = "SELECT documents.GET_USER_ID from dual";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			ResultSet sidResultSet = statement.executeQuery();
			if (sidResultSet.next()) {
				return sidResultSet.getInt(1);
			} else {
				throw new CarabiException("could not get Carabi user ID");
			}
		}
	}
}
