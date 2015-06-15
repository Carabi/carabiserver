package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
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
@Path("chat")
@RequestScoped
public class Chat {
	
	@Context
	private UriInfo context;
	
	@EJB private ChatBean chatBean;
	@EJB private UsersControllerBean uc;
	private static final Logger logger = CarabiLogging.getLogger(Chat.class);
	
	/**
	 * Обработка POST-зопросов (отправка сообщения).
	 * Пример запроса:
	 * <pre>
POST /carabiserver/webresources/chat/send?token=token&loginReceiver=mm HTTP/1.1
Host: appl.cara.bi
Connection: close
Content-type: text/plain; charset=utf-8
Content-Length: 66

Отправляемое в Караби-чат сообщение
	 * </pre> 
	 * Отправляет сообщение чата получателю, передавая его в теле пакета. Сообщение уходит от имени пользователя, под
	 * которым произведена авторизация. Можно задать расширение (см. так же 
	 * {@link #sendChatMessageExtended(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)}/
	 * @param token токен авторизации отправителя
	 * @param loginReceiver логин получателя
	 * @param extensionType тип расширения (необязательный параметр)
	 * @param extensionValue значение расширения (необязательный параметр)
	 * @param markReadStr если "true" -- сразу помечать сообщение прочитанным
	 * @param messageText текст сообщения (тело пакета)
	 * @return ID отправленного сообщения
	 */
	@POST
	@Path(value = "send")
	@Consumes("text/plain")
	public String sendChatMessage(
			@QueryParam("token") String token,
			@QueryParam("loginReceiver") String loginReceiver,
			@DefaultValue("") @QueryParam("extensionType") String extensionType,
			@DefaultValue("") @QueryParam("extensionValue") String extensionValue,
			@DefaultValue("false") @QueryParam("markRead") String markReadStr,
			String messageText
		) {
		String[] receiversArray;
		if (loginReceiver.contains(";")) {
			receiversArray = loginReceiver.split(";");
		} else {
			receiversArray = new String[] {loginReceiver};
		}
		boolean markRead = Boolean.valueOf(markReadStr);
		try (UserLogon logon = uc.tokenAuthorize(token, false)){
			CarabiUser sender = logon.getUser();
			Integer extensionTypeId = chatBean.getExtensionTypeId(extensionType, logon);
			Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText, extensionTypeId, extensionValue, markRead);
			return StringUtils.join(sentMessagesId, ";");
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
	/**
	 * Обработка POST-зопросов (репликация сообщения).
	 * Пример запроса:
	 * <pre>
POST /carabiserver/webresources/chat/replicate?token=token&loginSender=zao&loginReceiver=mm HTTP/1.1
Host: appl.cara.bi
Connection: close
Content-type: text/plain; charset=utf-8
Content-Length: 68

Реплицируемое в Караби-чат сообщение
	 * </pre> 
	 * Отправляет сообщение чата получателю, передавая его в теле пакета. Сообщение уходит от имени пользователя loginSender.
	 * Можно задать расширение (см. так же {@link #replicateChatMessageExtended(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)}/
	 * @param token токен авторизации приложения
	 * @param loginSender логин отправителя
	 * @param loginReceiver логин получателя
	 * @param extensionType тип расширения (необязательный параметр)
	 * @param extensionValue значение расширения (необязательный параметр)
	 * @param markReadStr если "true" -- сразу помечать сообщение прочитанным
	 * @param messageText текст сообщения (тело пакета)
	 * @return ID отправленного сообщения
	 */
	@POST
	@Path(value = "replicate")
	@Consumes("text/plain")
	public String replicateChatMessage(
			@QueryParam("token") String token,
			@QueryParam("loginSender") String loginSender,
			@QueryParam("loginReceiver") String loginReceiver,
			@DefaultValue("") @QueryParam("extensionType") String extensionType,
			@DefaultValue("") @QueryParam("extensionValue") String extensionValue,
			@DefaultValue("false") @QueryParam("markRead") String markReadStr,
			String messageText
		) {
		String[] receiversArray;
		if (loginReceiver.contains(";")) {
			receiversArray = loginReceiver.split(";");
		} else {
			receiversArray = new String[] {loginReceiver};
		}
		boolean markRead = Boolean.valueOf(markReadStr);
		try {
			UserLogon administrator = uc.getUserLogon(token);
			if (administrator == null || !administrator.isPermanent()) {
				throw new RestException("unknown token", Response.Status.UNAUTHORIZED);
			}
			CarabiUser sender = uc.findUser(loginSender);
			Integer extensionTypeId = chatBean.getExtensionTypeId(extensionType, administrator);
			Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText, extensionTypeId, extensionValue, markRead);
			return StringUtils.join(sentMessagesId, ";");
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
	
	/**
	 * Обработка POST-зопросов (отправка расширенного сообщения).
	 * Пример запроса:
	 * <pre>
POST /carabiserver/webresources/chat/send/extended?token=token&loginSender=zao&loginReceiver=mm&extensionType=task HTTP/1.1
Host: appl.cara.bi
Connection: close
Content-type: text/plain; charset=utf-8
Content-Length: xxx

содержимое расширения в формате, устанавливаемом клиентами (например, произвольный текст, base64, xml, json)
	 * </pre>
	 * 
	 * Отправляет расширенное сообщение чата получателю, передавая в теле пакета содержимое расширения.
	 * Сообщение уходит от имени пользователя, под которым произведена авторизация. Можно задать содержимое
	 * основного текста (по умолчанию пустой).
	 * @param token токен авторизации отправителя
	 * @param loginReceiver логин получателя
	 * @param extensionType тип расширения
	 * @param messageText текст сообщения (необязательный параметр)
	 * @param extensionValue значение расширения (тело пакета)
	 * @param markReadStr если "true" -- сразу помечать сообщение прочитанным
	 * @return ID отправленного сообщения
	 */
	@POST
	@Path(value = "send/extended")
	@Consumes("text/plain")
	public String sendChatMessageExtended(
			@QueryParam("token") String token,
			@QueryParam("loginReceiver") String loginReceiver,
			@QueryParam("extensionType") String extensionType,
			@DefaultValue("") @QueryParam("messageText") String messageText,
			@DefaultValue("false") @QueryParam("markRead") String markReadStr,
			String extensionValue
		) {
		String[] receiversArray;
		if (loginReceiver.contains(";")) {
			receiversArray = loginReceiver.split(";");
		} else {
			receiversArray = new String[] {loginReceiver};
		}
		boolean markRead = Boolean.valueOf(markReadStr);
		try (UserLogon logon = uc.tokenAuthorize(token, false)){
			CarabiUser sender = logon.getUser();
			Integer extensionTypeId = chatBean.getExtensionTypeId(extensionType, logon);
			Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText, extensionTypeId, extensionValue, markRead);
			return StringUtils.join(sentMessagesId, ";");
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
	/**
	 * Обработка POST-зопросов (репликация расширенного сообщения).
	 * Пример запроса:
	 * <pre>
POST /carabiserver/webresources/chat/replicate/extended?token=token&loginSender=zao&loginReceiver=mm&extensionType=task HTTP/1.1
Host: appl.cara.bi
Connection: close
Content-type: text/plain; charset=utf-8
Content-Length: xxx

содержимое расширения в формате, устанавливаемом клиентами (например, произвольный текст, base64, xml, json)
	 * </pre> 
	 * Отправляет расширенное сообщение чата получателю, передавая в теле пакета содержимое расширения.
	 * Сообщение уходит от имени пользователя loginSender. Можно задать содержимое
	 * основного текста (по умолчанию пустой).
	 * @param token токен авторизации отправителя
	 * @param loginReceiver логин получателя
	 * @param loginSender логин получателя
	 * @param extensionType тип расширения 
	 * @param messageText текст сообщения (необязательный параметр)
	 * @param extensionValue значение расширения (тело пакета)
	 * @param markReadStr если "true" -- сразу помечать сообщение прочитанным
	 * @return ID отправленного сообщения
	 */
	@POST
	@Path(value = "replicate/extended")
	@Consumes("text/plain")
	public String replicateChatMessageExtended(
			@QueryParam("token") String token,
			@QueryParam("loginSender") String loginSender,
			@QueryParam("loginReceiver") String loginReceiver,
			@QueryParam("extensionType") String extensionType,
			@DefaultValue("") @QueryParam("messageText") String messageText,
			@DefaultValue("false") @QueryParam("markRead") String markReadStr,
			String extensionValue
		) {
		String[] receiversArray;
		if (loginReceiver.contains(";")) {
			receiversArray = loginReceiver.split(";");
		} else {
			receiversArray = new String[] {loginReceiver};
		}
		boolean markRead = Boolean.valueOf(markReadStr);
		try {
			UserLogon administrator = uc.getUserLogon(token);
			if (administrator == null || !administrator.isPermanent()) {
				throw new RestException("unknown token", Response.Status.UNAUTHORIZED);
			}
			CarabiUser sender = uc.findUser(loginSender);
			Integer extensionTypeId = chatBean.getExtensionTypeId(extensionType, administrator);
			Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText, extensionTypeId, extensionValue, markRead);
			return StringUtils.join(sentMessagesId, ";");
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
	
	@GET
	@Path(value = "getUnreadMessagesCount")
	public String getMessagesCount(
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("login") String login,
			@DefaultValue("") @QueryParam("loginSender") String loginSender,
			@DefaultValue("") @QueryParam("loginReceiver") String loginReceiver,
			String messageText
		) {
		try(UserLogon logon = uc.tokenAuthorize(token, false)) {
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
		} catch (RegisterException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new RestException("unknown user or token", Response.Status.UNAUTHORIZED);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
}
