package ru.carabi.server.soap;

import java.io.IOException;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.kernel.EventerBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * Служебный сервис для работы TCP Eventer и его клиентов.
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "EventerService")
public class EventerService {
	
	@EJB private EventerBean eventerBean;
	
	@EJB private UsersControllerBean usersController;
	
	/**
	 * Получить токен для авторизации в эвентере.
	 * @param token токен текущей сессии в SOAP, полученный в {@link GuestService}
	 * @return токен параллельной сессии для Eventer
	 */
	@WebMethod(operationName = "getEventerToken")
	public String getEventerToken(@WebParam(name = "token") String token) {
		return eventerBean.getEventerToken(token);
	}
	
	/**
	 * Отправить событие клиенту через Eventer.
	 * @param token Токен авторизации программы, отправляющей событие
	 * @param schema БД, из которой идёт событие
	 * @param login логин клиента
	 * @param eventcode код события
	 * @param message текст события
	 * @throws IOException 
	 * @throws CarabiException При сбое шифрования, либо если зашифрованный пакет с метаданными больше стандартного пакета Eventer-a (фрагментация пока не поддерживается)
	 */
	@WebMethod(operationName = "fireEvent")
	public void fireEvent(
			@WebParam(name = "token") String token,
			@WebParam(name = "schema") String schema,
			@WebParam(name = "login") String login,
			@WebParam(name = "eventcode") short eventcode,
			@WebParam(name = "message") String message) throws IOException, CarabiException {
		UserLogon administrator = usersController.getUserLogon(token);
		if (administrator == null || !administrator.isPermanent()) {
			return;
		}
		eventerBean.fireEvent(schema, login, eventcode, message);
	}
}
