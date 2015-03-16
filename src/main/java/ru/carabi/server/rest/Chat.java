package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.PathParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.enterprise.context.RequestScoped;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.kernel.ChatBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Работа с Carabi-чатом.
 * Вызывается по URL 
 * <pre>{адрес сервера}/webresources/chat/{вариант использования}</pre>
 * Параметры при добавлении:
 * <ul>
 * <li>
 *	варианты использования:
 *	<ul>
 *	<li>send &mdash; отправка (авторизация по токену отправителя, логин отправителя не указывается)</li>
 *	<li>replicate &mdash; репликация (авторизация по токену программы, указывается логин отправителя)</li>
 *	</ul>
 * </li>
 * <li>token &mdash; токен (согласно action: пользовательской сессии или клиентского приложения)</li>
 * <li>loginSender &mdash; логин отправителя (указывать в соответствии с action)</li>
 * <li>loginReceiver &mdash; логин получателя или список логинов получателей, разделённых ';'</li>
 * <li>текст сообщения передаётся в теле POST-запроса</li>
 * </ul>
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("chat/{action}")
@RequestScoped
public class Chat {
	
	@Context
	private UriInfo context;
	
	@EJB private ChatBean chatBean;
	@EJB private UsersControllerBean uc;
	private static final Logger logger = CarabiLogging.getLogger(Chat.class);
	
	/**
	 * Обработка POST-зопросов (отправка и репликация).
	 * Пример запроса:
	 * <pre>
POST /carabiserver/webresources/chat/replicate?token=token&loginSender=zao&loginReceiver=mm HTTP/1.1
Host: appl.cara.bi
Connection: close
Content-type: text/plain; charset=utf-8
Content-Length: 68

Реплицируемое в Караби-чат сообщение
	 * </pre> 
	 */
	@POST
	@Consumes("text/plain")
	public String addChatMessage(
			@PathParam("action") String action,
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("loginSender") String loginSender,
			@DefaultValue("") @QueryParam("loginReceiver") String loginReceiver,
			String messageText
		) {
		String[] receiversArray;
		if (loginReceiver.contains(";")) {
			receiversArray = loginReceiver.split(";");
		} else {
			receiversArray = new String[] {loginReceiver};
		}
		try {
			if ("send".equals(action)) {
				try (UserLogon logon = uc.tokenAuthorize(token, false)) {
					CarabiUser sender = logon.getUser();
					Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText);
					return StringUtils.join(sentMessagesId, ";");
				}
			} else if ("replicate".equals(action)) {
				UserLogon administrator = uc.getUserLogon(token);
				if (administrator == null || !administrator.isPermanent()) {
					throw new RestException("unknown token", Response.Status.UNAUTHORIZED);
				}
				CarabiUser sender = uc.findUser(loginSender);
				Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText);
				return StringUtils.join(sentMessagesId, ";");
			} else {
				throw new RestException("unknown action", Response.Status.NOT_FOUND);
			}
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
	
	@GET
	public String getChatMessage(
			@PathParam("action") String action,
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("login") String login,
			@DefaultValue("") @QueryParam("loginSender") String loginSender,
			@DefaultValue("") @QueryParam("loginReceiver") String loginReceiver,
			String messageText
		) {
		try(UserLogon logon = uc.tokenAuthorize(token, false)) {
			if ("getUnreadMessagesCount".equals(action)) {
				if (logon.isPermanent()) {
					UserLogon targetLogon = new UserLogon();
					targetLogon.setUser(uc.findUser(login));
					targetLogon.setAppServer(Settings.getCurrentServer());
					targetLogon = uc.addUser(targetLogon);
					Long unreadMessagesCount;
					try {
						unreadMessagesCount = chatBean.getUnreadMessagesCount(targetLogon);
					} catch (Exception e){
						return e.getMessage();
					} finally {
						uc.removeUser(targetLogon.getToken(), true);
					}
					return "" + unreadMessagesCount;
				} else {
					return "" + chatBean.getUnreadMessagesCount(logon);
				}
			} else {
				throw new RestException("unknown action", Response.Status.NOT_FOUND);
			}
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}

}
