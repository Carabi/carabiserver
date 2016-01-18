package ru.carabi.server.kernel;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.soap.SoapUserInfo;

/**
 * Методы для авторизации в неядровой БД.
 * @author sasha<kopilov.ad@gmail.com>
 */
public interface AuthorizeSecondary {
	
	/**
	 * Содержит ли неядровая база собственнывй список пользователей.
	 * @return Содержит ли неядровая база собственнывй список пользователей
	 */
	public boolean hasUsersList();
	
	/**
	 * Поддерживает / использует ли неядровая база собственную авторизацию для
	 * выполнения хранимых запросов.
	 * @return 
	 */
	public boolean supportsAuthorize();
			
	/**
	 * Авторизация подключения в БД.
	 * Запрос к неядровой БД, необходимый, чтобы PL/SQL-функции,
	 * содержащие бизнес-логику, принимали сессию авторизованного пользователя
	 * @param connection подключение, в котором надо обозначить пользователя
	 * @param logon ядровая сессия авторизуемого пользователя
	 */
	public void authorizeUser(Connection connection, UserLogon logon) throws SQLException;
	
	/**
	 * Возвращает ID пользователя в неядровой БД.
	 * @param connection текущее подключение к БД.
	 * @param login логин пользователя
	 * @return ID пользователя в неядровой БД. -1, если пользователь не найден
	 * или если БД не содержит пользовательскую базу.
	 */
	public long getUserID(Connection connection, String login);
	
	/**
	 * Получение подробной выборки о пользователе из БД.
	 * @param connection текущее подключение к БД.
	 * @param login логин пользователя
	 * @return выборка в виде хеш-таблицы, null, если пользователь не найден
	 */
	public Map<String, ?> getDetailedUserInfo(Connection connection, String login);
	
	/**
	 * Возвращает объект SoapUserInfo, заполненный данными из метода getDetailedUserInfo.
	 * Конкретные поля зависят от конкретной БД.
	 * @param detailedUserInfo объект, возвращенный методом getDetailedUserInfo
	 * @return объект SoapUserInfo, заполненный данными из параметра detailedUserInfo
	 */
	public SoapUserInfo createSoapUserInfo(Map<String, ?> detailedUserInfo);
	
	/**
	 * Возвращает ID пользователя, имевшийся в выборке.
	 * Конкретное поле зависят от конкретной БД.
	 * @param detailedUserInfo объект, возвращенный методом getDetailedUserInfo
	 * @return ID пользователя, имевшийся в выборке.
	 */
	public long getSelectedUserID(Map<String, ?> detailedUserInfo);
	
	/**
	 * Возвращает строку, которой пользователь должен отображаться в графическом интерфейсе.
	 * @param currentUser объект из ядровой БД с данными о пользователе
	 * @param userInfo объект из неядровой БД с данными о пользователе
	 * @return отображение пользователя (обычно ФИО) из неядровой БД, если оно там есть, или из ядровой.
	 */
	public String getUserDisplayString(CarabiUser currentUser, Map<String, ?> userInfo);
	
	/**
	 * Получение ID текущего пользователя во вторичной БД.
	 * Устанавливается при вызове метода {@link #authorizeUser(java.sql.Connection, ru.carabi.server.UserLogon)}
	 * @param connection
	 * @return ID текущего пользователя во вторичной БД. -1, если БД не содержит бизнес-логику.
	 * @throws java.sql.SQLException
	 * @throws ru.carabi.server.CarabiException
	 */
	public long getCurrentUserId(Connection connection) throws SQLException, CarabiException;
}
