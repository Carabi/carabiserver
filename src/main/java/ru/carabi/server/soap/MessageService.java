package ru.carabi.server.soap;

import java.sql.SQLException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import ru.carabi.server.CarabiException;
import ru.carabi.server.CarabiOracleError;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * Сервис для получения коротких сообщений Web-сайтами.
 * Для получения сообщений настольными системами разрабатывается отдельная
 * подсистема (TCP Eventer) со служебным сервисом EventerService. Возможно,
 * к ней будут подвязаны и сайты по WebSocket.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "MessageService")
public class MessageService {
	
	@EJB
	private UsersControllerBean usersController;
	
//	@EJB
//	private MessagerBean messager;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager em;
	
	/**
	 * Получение числа непрочитанных сообщений в текущей схеме.
	 * @param token Авторизационный токен
	 * @return колечество непрочитанных сообщений в текущей схеме
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "countUnreadMessages")
	public int countUnreadMessages(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return 0;// messager.countUnreadMessages(logon);
		}
	}
	
	/**
	 * Список событий в системе.
	 * Что произошло в системе за последние дни/часы (для Eventer-а)
	 * @param token Авторизационный токен
	 * @return Список числа событий по категориям
	 * @throws CarabiException Если пользователь не авторизован или системный запрос не найден
	 * @throws ru.carabi.server.CarabiOracleError
	 */
	@WebMethod(operationName = "getNotifyMessages")
	public String getNotifyMessages(@WebParam(name = "token") String token) throws CarabiException, CarabiOracleError {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return Utls.tableToJson(new ArrayList<LinkedHashMap<String, ?>>()).build().toString();
		}
	}
	
	/**
	 * Получение числа непрочитанных сообщений во всех доступных схемах
	 * @param token авторизационный токен
	 * @return Json-массив объектов вида [{id: ID схемы, name:псевдоним схемы, unreadMessages: число непрочитанных сообщений}]
	 * @throws CarabiException
	 */
	@WebMethod(operationName = "collectUnreadMessages")
	public String collectUnreadMessages(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			CarabiUser user = logon.getUser();
			Map<ConnectionSchema, Integer> unread = new HashMap<>();//messager.collectUnreadMessages(user);
			JsonArrayBuilder result = Json.createArrayBuilder();
			for (ConnectionSchema schemaSource: unread.keySet()) {
				JsonObjectBuilder element = Json.createObjectBuilder();
				element.add("id", schemaSource.getId());
				element.add("name", schemaSource.getName());
				element.add("unreadMessages", unread.get(schemaSource));
				result.add(element);
			}
			try {
				logon.closeAllConnections();
			} catch (SQLException ex) {
				Logger.getLogger(MessageService.class.getName()).log(Level.SEVERE, null, ex);
			}
			return result.build().toString();
		}
	}
	
	/**
	 * Получение числа непрочитанных сообщений во всех доступных схемах
	 * @param token авторизационный токен
	 * @return Json-массив объектов вида [{id: ID схемы, name:псевдоним схемы, unreadMessages: число непрочитанных сообщений}]
	 * @throws CarabiException
	 */
	@WebMethod(operationName = "collectUnreadMessagesDetails")
	public String collectUnreadMessagesDetails(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			CarabiUser user = logon.getUser();
			Map<ConnectionSchema, List<LinkedHashMap<String, ?>>> unread = new HashMap<>();//messager.collectUnreadMessagesDetails(user);
			JsonArrayBuilder result = Json.createArrayBuilder();
			for (ConnectionSchema schemaSource: unread.keySet()) {
				JsonObjectBuilder element = Json.createObjectBuilder();
				element.add("id", schemaSource.getId());
				element.add("name", schemaSource.getName());
				element.add("sysname", schemaSource.getSysname());
				JsonArrayBuilder messagesFromSchema = Utls.tableToJson(unread.get(schemaSource));
				element.add("unreadMessages", messagesFromSchema);
				result.add(element);
			}
			return result.build().toString();
		}
	}
}
