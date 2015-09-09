package ru.carabi.server.kernel;

import java.io.Serializable;
import java.sql.Connection;
import java.util.HashMap;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.soap.SoapUserInfo;

/**
 * Пустая реализация интерфейса {@link AuthorizeSecondary}.
 * Используется при отсутствии неядровой БД.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public class AuthorizeSecondaryAbstract implements AuthorizeSecondary {
	
	/**
	 * Ничего не делает, так как нет БД
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
	public HashMap<String, ?> getDetailedUserInfo(Connection connection, String login) {
		return new HashMap<>();
	}
	
	/**
	 * Возвращает пустой объект SoapUserInfo.
	 * @param detailedUserInfo игнорируется
	 * @return пустой объект SoapUserInfo.
	 */
	@Override
	public SoapUserInfo createSoapUserInfo(HashMap<String, ?> detailedUserInfo) {
		return new SoapUserInfo();
	}
	
	/**
	 * Возвращает -1 (ID ненайденного пользователя).
	 * @param detailedUserInfo игнорируется
	 * @return -1 (ID ненайденного пользователя)
	 */
	@Override
	public long getUserID(HashMap<String, ?> detailedUserInfo) {
		return -1;
	}

	@Override
	public String getUserDisplayString(CarabiUser user, HashMap<String, ?> userInfo) {
		return user.getFirstname() + " " + user.getMiddlename() + " " + user.getLastname();
	}
}
