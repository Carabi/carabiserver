package ru.carabi.server.rest;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.PathParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.enterprise.context.RequestScoped;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.kernel.EventerBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * Отправка сообщений клиентам через Eventer.
 * Вызывается по URL 
 * <pre>{адрес сервера}/webresources/fire_event/{схема Караби, вызвавшая событие}/</pre>
 * Параметры:
 * <ul>
 * <li>token &mdash; токен клиентского приложения</li>
 * <li>login &mdash; логин адресата</li>
 * <li>eventcode &mdash; код события в Eventer</li>
 * <li>message &mdash; Текст события</li>
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("fire_event/{schema}")
@RequestScoped
public class FireEvent {
	private static final Logger logger = CarabiLogging.getLogger(FireEvent.class);
	
	@Context
	private UriInfo context;
	
	@EJB private EventerBean eventer;
	@EJB private UsersControllerBean usersController;
	
	/**
	 * Отправить событие в Eventer. Пример запроса:
	 * <pre>
POST /carabiserver_dev/webresources/fire_event/carabi?token=token&login=kop&eventcode=12 HTTP/1.1
Host: appl.cara.bi
Content-type: text/plain; charset=utf-8
Connection: close
Content-Length: 77

{"sender":"zao", "text":"Тестовое сообщение", "whole": true}
</pre>
	*/
	@POST
	@Consumes("text/plain")
	@Produces("text/plain")
	public String fireEventPlane(
			@PathParam("schema") String schemaName,
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("login") String login,
			@QueryParam("eventcode") String eventcode,
			String message
		) {
		try {
			UserLogon administrator = usersController.getUserLogon(token);
			if (administrator == null || !administrator.isPermanent()) {
				return "Unknown token";
			}
			eventer.fireEvent(schemaName, login, Short.valueOf(eventcode), message);
		} catch (IOException | CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
		}
		return "Event sent";
	}
	
	/**
	 * Отправить событие в Eventer. Пример запроса:
	 * <pre>
POST /carabiserver_dev/webresources/fire_event/carabi HTTP/1.1
Host: appl.cara.bi
Content-type:application/x-www-form-urlencoded; charset=utf-8
Connection: close
Content-Length: 120

token=token&login=kop&eventcode=12&message={"sender":"zao", "text":"Тестовое сообщение", "whole": true}
</pre>
	*/
	
	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("text/plain")
	public String fireEventEncoded(
			@PathParam("schema") String schemaName,
			@FormParam("token") String token,
			@DefaultValue("") @FormParam("login") String login,
			@FormParam("eventcode") String eventcode,
			@FormParam("message") String message
		) {
		try {
			UserLogon administrator = usersController.getUserLogon(token);
			if (administrator == null || !administrator.isPermanent()) {
				return "Unknown token";
			}
			eventer.fireEvent(schemaName, login, Short.valueOf(eventcode), message);
		} catch (IOException | CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
		}
		return "Event sent";
	}
}
