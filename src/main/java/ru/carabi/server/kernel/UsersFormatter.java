package ru.carabi.server.kernel;

import java.sql.Connection;
import java.util.HashMap;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.soap.SoapUserInfo;

/**
 * Методы, формирующие данные о пользователях.
 * Используются, прежде всего, при авторизации.
 * Возможны различные реализации в зависимости от нижелещащей (не ядровой) БД.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
public interface UsersFormatter {
	
	/**
	 * Возвращает ID пользователя в неядровой БД.
	 * @param connection текущее подключение к БД.
	 * @param login логин пользователя
	 * @return ID пользователя в неядровой БД. -1, если пользователь не найден.
	 */
	public long getUserID(Connection connection, String login);
	
	/**
	 * Получение подробной выборки о пользователе из БД.
	 * @param connection текущее подключение к БД.
	 * @param login логин пользователя
	 * @return выборка в виде хеш-таблицы, null, если пользователь не найден
	 */
	public HashMap<String, ?> getDetailedUserInfo(Connection connection, String login);
	
	/**
	 * Возвращает объект SoapUserInfo, заполненный данными из метода getDetailedUserInfo.
	 * Конкретные поля зависят от конкретной БД.
	 * @param detailedUserInfo объект, возвращенный методом getDetailedUserInfo
	 * @return объект SoapUserInfo, заполненный данными из параметра detailedUserInfo
	 */
	public SoapUserInfo createSoapUserInfo(HashMap<String, ?> detailedUserInfo);
	
	/**
	 * Возвращает ID пользователя, имевшийся в выборке.
	 * Конкретное поле зависят от конкретной БД.
	 * @param detailedUserInfo объект, возвращенный методом getDetailedUserInfo
	 * @return ID пользователя, имевшийся в выборке.
	 */
	public long getUserID(HashMap<String, ?> detailedUserInfo);
	
	/**
	 * Возвращает строку, которой пользователь должен отображаться в графическом интерфейсе.
	 * @param currentUser объект из ядровой БД с данными о пользователе
	 * @param userInfo объект из неядровой БД с данными о пользователе
	 * @return отображение пользователя (обычно ФИО) из неядровой БД, если оно там есть, или из ядровой.
	 */
	public String getUserDisplayString(CarabiUser currentUser, HashMap<String, ?> userInfo);
}
