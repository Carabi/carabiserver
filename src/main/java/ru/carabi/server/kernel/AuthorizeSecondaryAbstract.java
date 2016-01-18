package ru.carabi.server.kernel;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.soap.SoapUserInfo;

/**
 * Пустая реализация интерфейса {@link AuthorizeSecondary}.
 * Используется при отсутствии пользовательской базы в неядровой БД
 * или при отсутствии неядровой БД вообще.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public class AuthorizeSecondaryAbstract implements AuthorizeSecondary {
	
	@Override
	public boolean hasUsersList() {
		return false;
	}
	
	@Override
	public boolean supportsAuthorize() {
		return false;
	}
	
	/**
	 * Ничего не делает, так как БД отсутствует или не поддерживает авторизацию сессии
	 */
	@Override
	public void authorizeUser(Connection connection, UserLogon logon) {
	}
	
	/**
	 * Возвращает -1 (ID ненайденного пользователя).
	 * @param connection подключение к БД (ожидается пустое)
	 * @param login имя пользователя (игнорируется)
	 * @return -1 (ID ненайденного пользователя)
	 */
	@Override
	public long getUserID(Connection connection, String login) {
		return -1;
	}
	
	/**
	 * Возвращает пустую хеш-таблицу.
	 * @param connection подключение к БД (ожидается пустое)
	 * @param login имя пользователя (игнорируется)
	 * @return пустая выборка
	 */
	@Override
	public Map<String, ?> getDetailedUserInfo(Connection connection, String login) {
		return new ConcurrentHashMap<>();
	}
	
	/**
	 * Возвращает пустой объект SoapUserInfo.
	 * @param detailedUserInfo игнорируется
	 * @return пустой объект SoapUserInfo.
	 */
	@Override
	public SoapUserInfo createSoapUserInfo(Map<String, ?> detailedUserInfo) {
		return new SoapUserInfo();
	}
	
	/**
	 * Возвращает -1 (ID ненайденного пользователя).
	 * @param detailedUserInfo игнорируется
	 * @return -1 (ID ненайденного пользователя)
	 */
	@Override
	public long getSelectedUserID(Map<String, ?> detailedUserInfo) {
		return -1;
	}

	@Override
	public String getUserDisplayString(CarabiUser user, Map<String, ?> userInfo) {
		return user.getFirstname() + " " + user.getMiddlename() + " " + user.getLastname();
	}

	@Override
	public long getCurrentUserId(Connection connection) throws SQLException, CarabiException {
		return -1;
	}
}
