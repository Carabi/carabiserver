package ru.carabi.server.kernel;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.ws.rs.core.Response;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;
import me.lima.ThreadSafeDateParser;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.libs.CarabiEventType;
import ru.carabi.libs.CarabiFunc;
import static ru.carabi.libs.CarabiFunc.*;
import ru.carabi.server.CarabiException;
import ru.carabi.server.PermissionException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ChatExtendedMessageType;
import ru.carabi.server.entities.ChatMessage;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.MessagesGroup;
import ru.carabi.server.entities.UserRelation;
import ru.carabi.server.entities.UserRelationType;
import ru.carabi.server.kernel.oracle.CarabiDate;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.rest.RestException;
import ru.carabi.stub.CarabiException_Exception;
import ru.carabi.stub.ChatService;
import ru.carabi.stub.ChatService_Service;

/**
 *
 * @author sasha<kopilov.ad@gmail.com>
 */
@Singleton
public class ChatBean {
	private static final Logger logger = CarabiLogging.getLogger(ChatBean.class);
	
	@EJB UsersControllerBean uc;
	
	@EJB EventerBean eventer;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-chat")
	EntityManager emChat;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager emKernel;
	
	/**
	 * Отправка сообщения. Отправляет сообщение получателю и, при успешной доставке,
	 * записывает в отправленные отправителю
	 * @param sender
	 * @param receiver
	 * @param messageText
	 * @param senderAttachmentId ID вложения на стороне отправителя (если сообщение с вложением и отправитель его сохраняет)
	 * @param receiverAttachmentId ID вложения на стороне получателя (если сообщение с вложением)
	 * @param extensionTypeId ID типа расширения, если сообщение расширенное
	 * @param extensionValue содержимое расширения
	 * @param markRead сразу помечать сообщение прочитанным
	 * @return id сообщения на стороне отправителя
	 * @throws ru.carabi.server.CarabiException 
	 */
	public Long sendMessage(CarabiUser sender, CarabiUser receiver, String messageText, Long senderAttachmentId, Long receiverAttachmentId, Integer extensionTypeId, String extensionValue, boolean markRead) throws CarabiException {
		if (sender.equals(receiver)) { //пометки самому себе по умолчанию известны пользователю
			markRead = true;
		}
		CarabiAppServer receiverServer = getTargetUserServer(receiver);
		Long recievedMessageId;
		//Если целевой сервер -- текущий, вызываем функцию из Bean напрямую.
		if (Settings.getCurrentServer().equals(receiverServer)) {
			recievedMessageId = forwardMessage(sender, receiver, messageText, receiverAttachmentId, extensionTypeId, extensionValue, markRead);
		} else { //иначе по SOAP
			recievedMessageId = callForwardMessageSoap(receiverServer, sender, receiver, messageText, receiverAttachmentId, extensionTypeId, extensionValue, markRead);
		}
		if (recievedMessageId < 0) {
			throw new CarabiException("could not forward message");
		}
		CarabiAppServer senderServer = getTargetUserServer(sender);
		Long sentMessageId;
		//Аналогично для отправителя, если сообщение дошло получателю
		if (Settings.getCurrentServer().equals(senderServer)) {
			sentMessageId = putMessage(sender, sender, receiver, recievedMessageId, sender.getMainServer().getId(), messageText, senderAttachmentId, extensionTypeId, extensionValue, markRead);
		} else {
			sentMessageId = callPutMessageSoap(senderServer, sender, sender, receiver, recievedMessageId, sender.getMainServer().getId(), messageText, senderAttachmentId, extensionTypeId, extensionValue, markRead);
		}
		return sentMessageId;
	}
	
	/**
	 * Доставка сообщения получателю. Предполагается, что его база чата располагается
	 * на текущем сервере (если нет &mdash; вызывается {@link ChatService#forwardMessage(java.lang.String, java.lang.String, java.lang.String, java.lang.String) }
	 * соответствующего сервера). Сообщение записывается в базу (вложение должно быть записано ранее), а пользователь
	 * получает уведомление через Eventer.
	 * @param sender отправитель
	 * @param receiver получатель
	 * @param messageText текст сообщения (для вложений -- короткий комментарий, например, имя файла)
	 * @param attachmentId id существующего вложения
	 * @param extensionTypeId id типа расширения
	 * @param extensionValue значение расширения
	 * @param markRead сразу помечать сообщение прочитанным
	 * @return ID сообщения у получателя
	 */
	public Long forwardMessage(CarabiUser sender, CarabiUser receiver, String messageText, Long attachmentId, Integer extensionTypeId, String extensionValue, boolean markRead) throws CarabiException {
		CarabiAppServer receiverServer = getTargetUserServer(receiver);
		if (!Settings.getCurrentServer().equals(receiverServer)) {
			//Если мы не на сервере получателя -- вызываем функцию по SOAP
			return callForwardMessageSoap(receiverServer, sender, receiver, messageText, attachmentId, extensionTypeId, extensionValue, markRead);
		}
		Long messageId = putMessage(receiver, sender, receiver, null, null, messageText, attachmentId, extensionTypeId, extensionValue, markRead);
		return messageId;
	}

	private void messageToEventer(CarabiUser sender, CarabiUser receiver, boolean toReceiver, Long messageId) throws CarabiException {
		try {
			String eventText;
			JsonObjectBuilder eventTextBuild = Json.createObjectBuilder();
			eventTextBuild.add("sender", sender.getLogin());
			eventTextBuild.add("receiver", receiver.getLogin());
			eventTextBuild.add("id", messageId);
			eventText = eventTextBuild.build().toString();
			eventer.fireEvent("", toReceiver ? receiver.getLogin() : sender.getLogin(), CarabiEventType.chatMessage.getCode(), eventText);
		} catch (IOException ex) {
			logger.log(Level.SEVERE, null, ex);
		}
	}
	
	/**
	 * Запись сообщения в базу чата на текущем сервере.
	 * @param owner владелец ящика
	 * @param sender отправитель
	 * @param receiver получатель
	 * @param receivedMessageId Id полученного входящего сообщения, при записи парного ему отправленного
	 * @param receivedMessageServerId
	 * @param messageText текст сообщения
	 * @param attachmentId ID вложения, если есть
	 * @param extensionTypeId ID типа расширения, если есть
	 * @param extensionValue содержимое расширения, если есть
	 * @param markRead сразу помечать сообщение прочитанным
	 * @return Id новой записи
	 * @throws CarabiException 
	 */
	public Long putMessage(CarabiUser owner, CarabiUser sender, CarabiUser receiver, Long receivedMessageId, Integer receivedMessageServerId, String messageText, Long attachmentId, Integer extensionTypeId, String extensionValue, boolean markRead) throws CarabiException {
		ChatMessage chatMessage = new ChatMessage();
		chatMessage.setOwnerId(owner.getId());
		chatMessage.setSenderId(sender.getId());
		chatMessage.setSent(new Date());
		chatMessage.setReceiverId(receiver.getId());
		chatMessage.setReceivedMessageId(receivedMessageId);
		chatMessage.setReceivedMessageServerId(receivedMessageServerId);
		chatMessage.setMessageText(messageText);
		if (attachmentId != null) {
			chatMessage.setAttachment(emChat.find(FileOnServer.class, attachmentId));
		}
		chatMessage.setExtensionTypeId(extensionTypeId);
		chatMessage.setExtensionValue(extensionValue);
		if (markRead) {
			chatMessage.setReceived(new Date());
		}
		chatMessage = emChat.merge(chatMessage);
		emChat.flush();
		messageToEventer(sender, receiver, owner.equals(receiver), chatMessage.getId());
		return chatMessage.getId();
	}
	
	/**
	 * Выдаёт основной сервер пользователя или его компании. Если не задан &mdash; Выдаёт текущий, устанавливая его пользователю.
	 * @param user
	 * @return 
	 */
	private CarabiAppServer getTargetUserServer(CarabiUser user) {
		CarabiAppServer userServer = user.getMainServer();//проверяем индивидуальные настройкинастрой
		if (userServer == null) {
			Department department = user.getDepartment();//если индивидуальных нет -- возвращаем настройки корпорации
			if (department != null && department.getMainServer() != null) {
				return department.getMainServer();
			}//если нет настроек корпорации -- ставим основным текущий и возвращаеи
			userServer = Settings.getCurrentServer();
			user.setMainServer(userServer);
			user = emKernel.merge(user);
			emKernel.flush();
		}
		return userServer;
	}
	
	/**
	 * Пометка сообщения прочитанным/непрочитаным. Попутно ставит уведомление о доставке отправителю.
	 * @param receiverLogon сессия пользователя
	 * @param messagesList Строка с ID сообщения или JSON-массив
	 * @param read если true -- пометить прочитанным (и отправить уведомление), иначе -- снять пометку
	 * @throws ru.carabi.server.CarabiException 
	 */
	public void markRead(UserLogon receiverLogon, String messagesList, boolean read) throws CarabiException {
		logger.info(messagesList);
		//Функция должна выполняться на сервере владельца.
		final CarabiUser receiver = receiverLogon.getUser();
		CarabiAppServer targetServer = getTargetUserServer(receiver);
		final CarabiAppServer currentServer = Settings.getCurrentServer();
		List<Long> messageIdList = parseMessagesIdList(messagesList);
		if (!currentServer.equals(targetServer)) {
			callMarkReadSoap(targetServer, receiverLogon.getToken(), messagesList, read);
			return;
		}
		for (Long messageId: messageIdList) {//Валидация сообщений
			//Письмо должно принадлежать текущему пользователю, и он должен быть получателем
			ChatMessage receivedMessage = emChat.find(ChatMessage.class, messageId);
			if (receivedMessage == null) {
				throw new CarabiException("message " + messageId + " not found");
			}
			if (!receiver.getId().equals(receivedMessage.getOwnerId())) {
				throw new CarabiException("Message " + messageId + " does not belong to this user");
			}
			if (!receiver.getId().equals(receivedMessage.getReceiverId())) {
				throw new CarabiException("Message " + messageId + " is not income");
			}
		}
		//Маркируем письма получателя, раскладываем письма по отправителям
		Map<String, JsonArrayBuilder> messagesBySenders = new HashMap<>();
		Map<String, CarabiUser> senders = new HashMap<>();
		for (Long messageId: messageIdList) {
			ChatMessage receivedMessage = emChat.find(ChatMessage.class, messageId);
			if (read) {
				receivedMessage.setReceived(new Date());
			} else {
				receivedMessage.setReceived(null);
			}
			receivedMessage = emChat.merge(receivedMessage);
			Long senderId = receivedMessage.getSenderId();
			CarabiUser sender = emKernel.find(CarabiUser.class, senderId);
			String senderLogin;
			if (sender == null) {
				logger.warning("Unknown sender id " + senderId);
				senderLogin = "";
			} else {
				senderLogin = sender.getLogin();
				senders.put(senderLogin, sender);
			}
			JsonArrayBuilder sendersMessages = messagesBySenders.get(senderLogin);
			if (sendersMessages == null) {
				sendersMessages = Json.createArrayBuilder();
				messagesBySenders.put(senderLogin, sendersMessages);
			}
			sendersMessages.add(messageId);
		}
		emChat.flush();
		//отправляем события о прочитанных сообщениях
		for (String senderLogin: messagesBySenders.keySet()) {
			final JsonArrayBuilder senderMessages = messagesBySenders.get(senderLogin);
			JsonObject eventTextBuilt;//В связи с тем, что библиотека очищает содержимое senderMessages
			//при его добавлении в eventText, для повторного использования приходится доставать
			JsonObjectBuilder eventText = Json.createObjectBuilder();
			eventText.add("sender", senderLogin);
			eventText.add("receiver", receiver.getLogin());
			eventText.add("read", read);
			eventText.add("messagesList", senderMessages);
			eventTextBuilt = eventText.build();
			try {
				eventer.fireEvent("", receiver.getLogin(), CarabiEventType.chatMessageRead.getCode(), eventTextBuilt.toString());
			} catch (IOException ex) {
				Logger.getLogger(ChatBean.class.getName()).log(Level.SEVERE, null, ex);
			}
			if (!read) {
				//У отправителя не скидываем
			} else {
				//Берём отправителя, помечаем письмо в отправленных
				CarabiUser sender = senders.get(senderLogin);
				targetServer = sender.getMainServer();
				final JsonArray messagesListBuilt = eventTextBuilt.getJsonArray("messagesList");
				if (currentServer.equals(targetServer)) {
					markSentReceived(sender, receiver, messagesListBuilt.toString());
				} else {
					callMarkSentReceivedSoap(targetServer, senderLogin, receiver.getLogin(), messagesListBuilt.toString());
				}
			}
		}
	}
	
	public int markReadPrevios(UserLogon receiverLogon, CarabiUser sender, String date) throws CarabiException {
		final CarabiUser receiver = receiverLogon.getUser();
		CarabiAppServer targetServer = getTargetUserServer(receiver);
		final CarabiAppServer currentServer = Settings.getCurrentServer();
		if (!currentServer.equals(targetServer)) {
			return callMarkReadPreviosSoap(targetServer, receiverLogon.getToken(), sender.getLogin(), date);
		}
		Date sentBefore;
		try {
			CarabiDate carabiDate = new CarabiDate(date);
			sentBefore = new Date(carabiDate.getTime());
		} catch (ParseException ex) {
			sentBefore = new Date();
		}
		Query getOldUnreadMessages = emChat.createNamedQuery("getOldUnreadMessages");
		getOldUnreadMessages.setParameter("user", receiver.getId());
		getOldUnreadMessages.setParameter("sender", sender.getId());
		getOldUnreadMessages.setParameter("sentBefore", sentBefore);
		List resultList = getOldUnreadMessages.getResultList();
		JsonArrayBuilder messagesList = Json.createArrayBuilder();
		for (Object result: resultList) {
			Long unreadMessageId = (Long) result;
			messagesList.add(unreadMessageId);
		}
		final JsonArray messagesListBuilt = messagesList.build();
		markRead(receiverLogon, messagesListBuilt.toString(), true);
		return messagesListBuilt.size();
	}
	/**
	 * Установка уведомления о доставке в отправленном сообщении.
	 * @param sender
	 * @param receiver
	 * @param messagesList
	 */
	public void markSentReceived(CarabiUser sender, CarabiUser receiver, String messagesList) throws CarabiException {
		//При необходимости переходим на сервер отправителя
		CarabiAppServer targetServer = getTargetUserServer(sender);
		if (!Settings.getCurrentServer().equals(targetServer)) {
			callMarkSentReceivedSoap(targetServer, sender.getLogin(), receiver.getLogin(), messagesList);
			return;
		}
		List<Long> messageIdList = parseMessagesIdList(messagesList);
		JsonArrayBuilder sentMessages = Json.createArrayBuilder();
		for (Long messageId: messageIdList) {
			//ищем отправленное письмо по ID и базе полученного
			TypedQuery<ChatMessage> getSentByReceived = emChat.createNamedQuery("getSentByReceived", ChatMessage.class);
			getSentByReceived.setParameter("received", messageId);
			getSentByReceived.setParameter("server", receiver.getMainServer().getId());
			try {
				ChatMessage sentMessage = getSentByReceived.getSingleResult();
				//проверяем, что письмо принадлежит отправителю
				if (!sender.getId().equals(sentMessage.getOwnerId())) {
					throw new CarabiException("Message does not belong to this user");
				}
				if (!sender.getId().equals(sentMessage.getSenderId())) {
					throw new CarabiException("Message is not outcome");
				}
				//помечаем
				sentMessage.setReceived(new Date());
				emChat.merge(sentMessage);
				sentMessages.add(sentMessage.getId());
			} catch (NoResultException e) {
				//отправитель удалил отправленное раньше, чем получатель его прочитал
			}
		}
		emChat.flush();
		try {
			//отправляем событие, что сообщение получено
			JsonObjectBuilder eventText = Json.createObjectBuilder();
			eventText.add("sender", sender.getLogin());
			eventText.add("receiver", receiver.getLogin());
			eventText.add("read", true);
			eventText.add("messagesList", sentMessages);
			eventer.fireEvent("", sender.getLogin(), (short)13, eventText.build().toString());
		} catch (IOException ex) {
			Logger.getLogger(ChatBean.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public Long getUnreadMessagesCount(UserLogon client) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetUnreadMessagesCountSoap(targetServer, client.getToken());
		}
		Query getUnreadMessagesCount = emChat.createNamedQuery("getUnreadMessagesCount");
		getUnreadMessagesCount.setParameter("user", client.getUser().getId());
		List count = getUnreadMessagesCount.getResultList();
		return (Long) count.get(0);
	}
	
	public String getUnreadMessagesSenders(UserLogon client) throws CarabiException {
		return getUnreadMessagesSenders_Internal(client, false);
	}
	
	public String getUnreadMessagesSendersDetailed(UserLogon client) throws CarabiException {
		return getUnreadMessagesSenders_Internal(client, true);
	}
	
	private String getUnreadMessagesSenders_Internal(UserLogon client, boolean addLastMessages) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			if (addLastMessages) {
				return callGetUnreadMessagesSendersDetailedSoap(targetServer, client.getToken());
			} else {
				return callGetUnreadMessagesSendersSoap(targetServer, client.getToken());
			}
		}
		Query getUnreadMessagesSenders = emChat.createNamedQuery("getUnreadMessagesSenders", Object[].class);
		getUnreadMessagesSenders.setParameter("user", client.getUser().getId());
		List<Object[]> sendersMessages = getUnreadMessagesSenders.getResultList();//список ID отправителей и количества непрочитанных сообщений
		JsonObjectBuilder result = Json.createObjectBuilder();
		for (Object[] senderMessages: sendersMessages) {
			Object senderId = senderMessages[0];
			CarabiUser sender = emKernel.find(CarabiUser.class, senderId);
			String login;
			if (sender == null) {
				login = senderId.toString();
			} else {
				login = sender.getLogin();
			}
			if (addLastMessages) {
				JsonObjectBuilder userMessageAndCount = Json.createObjectBuilder();
				userMessageAndCount.add("messages", (Long)senderMessages[1]);
				Query getLastUserMessages = emChat.createNamedQuery("getLastUserMessages");
				getLastUserMessages.setMaxResults(1);
				getLastUserMessages.setParameter("user", client.getUser().getId());
				getLastUserMessages.setParameter("sender", sender.getId());
				List<Object[]> LastUserMessages = getLastUserMessages.getResultList();
				if (LastUserMessages.isEmpty()) {
					userMessageAndCount.add("lastMessage", "");
				} else {
					Object[] lastUserMessage = LastUserMessages.get(0);
					userMessageAndCount.add("lastMessageId", (Long)lastUserMessage[0]);
					userMessageAndCount.add("lastMessage", (String)lastUserMessage[1]);
				}
				result.add(login, userMessageAndCount);
			} else {
				result.add(login, (Long)senderMessages[1]);
			}
		}
		return result.build().toString();
	}
	
	public String getMessage(UserLogon client, Long messageId, boolean read) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetMessageSoap(targetServer, client.getToken(), messageId, read);
		}
		//Письмо должно принадлежать текущему пользователю
		ChatMessage message = emChat.find(ChatMessage.class, messageId);
		if (message == null) {
			throw new CarabiException("message " + messageId + " not found");
		}
		final CarabiUser user = client.getUser();
		if (!user.getId().equals(message.getOwnerId())) {
			throw new CarabiException("Message does not belong to this user");
		}
		//если пользоватль -- получатель, можно ставить пометку о прочтении
		if (read && user.getId().equals(message.getReceiverId())) {
			markRead(client, messageId.toString(), true);
		}
		return message.getMessageText();
	}
	
	public String getMessageDetails(UserLogon client, Long messageId, boolean read, int crop) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetMessageDetailsSoap(targetServer, client.getToken(), messageId, read, crop);
		}
		//Письмо должно принадлежать текущему пользователю
		ChatMessage message = emChat.find(ChatMessage.class, messageId);
		if (message == null) {
			throw new CarabiException("message " + messageId + " not found");
		}
		final CarabiUser user = client.getUser();
		if (!user.getId().equals(message.getOwnerId())) {
			throw new CarabiException("Message does not belong to this user");
		}
		//если пользоватль -- получатель, можно ставить пометку о прочтении
		if (read && user.getId().equals(message.getReceiverId())) {
			markRead(client, messageId.toString(), true);
		}
		JsonObjectBuilder result = Json.createObjectBuilder();
		CarabiUser sender = emKernel.find(CarabiUser.class, message.getSenderId());
		result.add("sender", sender.getLogin());
		CarabiUser receiver = emKernel.find(CarabiUser.class, message.getReceiverId());
		result.add("receiver", receiver.getLogin());
		result.add("sent", ThreadSafeDateParser.format(message.getSent(), CarabiDate.pattern));
		if (message.getReceived() == null) {
			result.addNull("received");
		} else {
			result.add("received", ThreadSafeDateParser.format(message.getReceived(), CarabiDate.pattern));
		}
		String messageText = message.getMessageText();
		if (crop > 0 && messageText.length() > crop) {
			result.add("message", messageText.substring(0, crop));
			result.add("messageIsFull", false);
		} else {
			result.add("message", messageText);
			result.add("messageIsFull", true);
		}
		result.add("attachment", message.getAttachment() != null);
		Utls.addJsonObject(result, "extensionType", getMessageExtensionType(message));
		Utls.addJsonObject(result, "extensionValue", message.getExtensionValue());
		return result.build().toString();
	}
	
	public String getContactList(UserLogon client, String search) throws CarabiException {
		//Выбираем пользователей
		TypedQuery<CarabiUser> getUsersList;
		if (search != null && !search.isEmpty()) {
			getUsersList = emKernel.createNamedQuery("getActiveUsersListSearch", CarabiUser.class);
			getUsersList.setParameter("search", "%" + search.toUpperCase() + "%");
		} else {
			getUsersList = emKernel.createNamedQuery("getActiveUsersList", CarabiUser.class);
		}
		List<CarabiUser> usersList = getUsersList.getResultList();
		return printUsersForOutput(client, usersList, null, false).toString();
	}
	
	public String getRelatedUsersList(UserLogon client, String relationsStr, boolean conjunction) throws CarabiException {
		if (StringUtils.isEmpty(relationsStr)) {
			return getRelatedUsers(client);
		}
		List<String> relations = new ArrayList<>();
		try {
			JsonReader reader = Json.createReader(new StringReader(relationsStr));
			JsonArray relationsJson = reader.readArray();
			final int size = relationsJson.size();
			if (size == 0) {
				return getRelatedUsers(client);
			}
			for (int i=0; i<size; i++) {
				final String relation = relationsJson.getString(i);
				relations.add(relation);
			}
		} catch (JsonException ex) {
			relations.add(relationsStr);
		}
		Query getRelationTypesIds = emKernel.createNamedQuery("getRelationTypesIds");
		getRelationTypesIds.setParameter("relations", relations);
		List relationsIds = getRelationTypesIds.getResultList();
		Query getUsersList = null;
		if (conjunction) {
			String sql = "select * from CARABI_USER where  USER_ID in (\n" +
				"	select RELATED_USER_ID from USER_RELATION\n";
			String term = "";
			for (Object relationId: relationsIds) {
				sql += "	left join RELATION_HAS_TYPE T"+relationId + " on USER_RELATION.USER_RELATION_ID = T"+relationId+".USER_RELATION_ID\n";
				term += "and T"+relationId+".RELATION_TYPE_ID = " + relationId;
			}
			sql += "	where MAIN_USER_ID = ? " + term + "\n)";
			getUsersList = emKernel.createNativeQuery(sql, CarabiUser.class);
			
		} else {
			String sql = "select * from CARABI_USER where  USER_ID in (\n" +
					"	select RELATED_USER_ID from USER_RELATION\n" +
					"	left join RELATION_HAS_TYPE on USER_RELATION.USER_RELATION_ID = RELATION_HAS_TYPE.USER_RELATION_ID\n" +
					"	where MAIN_USER_ID = ?";
			if (!relationsIds.isEmpty()) {
				sql = sql + " and RELATION_TYPE_ID in (" + StringUtils.join(relationsIds, ", ") +")\n";
			}
			sql = sql + "\n)";
			getUsersList = emKernel.createNativeQuery(sql, CarabiUser.class);
		}
		getUsersList.setParameter(1, client.getUser().getId());
		//Выбираем пользователей
//		List<String> relations = new ArrayList<>();
//		relations.add("searchable");
//		relations.add("favourite");
//		getUsersList.setParameter("relations", relations);
		List<CarabiUser> usersList = getUsersList.getResultList();
		return printUsersForOutput(client, usersList, null, true).toString();
	}

	private String getRelatedUsers(UserLogon client) throws CarabiException {
		TypedQuery<CarabiUser> getUsersList = emKernel.createNamedQuery("getRelatedUsersList", CarabiUser.class);
		getUsersList.setParameter("user", client.getUser());
		List<CarabiUser> usersList = getUsersList.getResultList();
		return printUsersForOutput(client, usersList, null, true).toString();
	}
	
	public String getContact(UserLogon client, String login) throws CarabiException {
		List<CarabiUser> usersList = new ArrayList<>(1);
		usersList.add(uc.findUser(login));
		JsonObject userForOutput = printUsersForOutput(client, usersList, null, true);
		return Utls.redim(userForOutput).toString();
	}
	
	public String getLastInterlocutors(UserLogon client, int size, String afterDateStr, String search) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetLastInterlocutorsSoap(targetServer, client.getToken(), size, afterDateStr, search);
		}
		CarabiDate afterDate = parceDate(afterDateStr, "01.01.1970");
		//берём недавно полученные письма
		Query getRecentlyMessagesData = emChat.createNamedQuery("getRecentlyMessagesData", Object[].class);
		getRecentlyMessagesData.setParameter("user", client.getUser().getId());
		getRecentlyMessagesData.setParameter("recently", afterDate);//TODO: тут мы читаем ВСЕ письма в обратном порядке, а надо только одно -- исправить!
		
		List<Object[]> messagesMetadata = getRecentlyMessagesData.getResultList();//список писем (отправитель, получатель, дата) в порядке устаревания
		List<Long> interlocutorsIdOrdered = new ArrayList();
		Set<Long> interlocutorsIdSet = new HashSet();
		Map<Long, Date> interlocutorsLastContact = new HashMap<>();
		final Long ownerId = client.getUser().getId();
		//получаем упорядоченный список id собеседников и дату последнего сообщения от каждого из них
		for (Object[] messageMetadata: messagesMetadata) {
			Long senderId = (Long) messageMetadata[0];
			Long receiverId = (Long) messageMetadata[1];
			Long interlocutorId = ownerId.equals(senderId) ? receiverId : senderId;
			if (!interlocutorsIdSet.contains(interlocutorId)) {
				interlocutorsIdOrdered.add(interlocutorId);
				interlocutorsIdSet.add(interlocutorId);
				interlocutorsLastContact.put(interlocutorId, (Date) messageMetadata[2]);
			}
		}
		
		List<CarabiUser> interlocutorsOrdered;
		if (!interlocutorsIdOrdered.isEmpty()) {
			//получаем данные особеседниках
			List<CarabiUser> users;
			if (search != null && !search.isEmpty()) {
				
				TypedQuery<CarabiUser> getSelectedUsersListSearch = emKernel.createNamedQuery("getSelectedUsersListSearch", CarabiUser.class);
				getSelectedUsersListSearch.setParameter("idlist", interlocutorsIdOrdered);
				getSelectedUsersListSearch.setParameter("search", "%" + search.toUpperCase() + "%");
				users = getSelectedUsersListSearch.getResultList();
			} else {
				TypedQuery<CarabiUser> getSelectedUsersList = emKernel.createNamedQuery("getSelectedUsersList", CarabiUser.class);
				getSelectedUsersList.setParameter("idlist", interlocutorsIdOrdered);
				users = getSelectedUsersList.getResultList();
			}

			//восстанавливаем порядок устаревания диалогов
			Map<Long, CarabiUser> usersPerId = new HashMap<>();
			for (CarabiUser sender: users) {
				usersPerId.put(sender.getId(), sender);
			}
			interlocutorsOrdered = new ArrayList<>(interlocutorsIdOrdered.size());
			int i = 0;
			for (Long senderId: interlocutorsIdOrdered) {
				CarabiUser user = usersPerId.get(senderId);
				if (user != null) {
					interlocutorsOrdered.add(user);
					i++;
				}
				if (size > 0 && i == size) {
					break;
				}
			}
		} else {
			interlocutorsOrdered = new ArrayList<>(0);
		}
		return printUsersForOutput(client, interlocutorsOrdered, interlocutorsLastContact, true).toString();
	}

	private CarabiDate parceDate(String dateStr, String defaultVal) throws CarabiException {
		CarabiDate date;
		if (dateStr != null && !dateStr.isEmpty()) {
			try {//парсинг даты
				date = new CarabiDate(dateStr);
			} catch (IllegalArgumentException | ParseException e) {
				throw new CarabiException("Illegal date: " + dateStr);
			}
		} else {
			try {
				date = new CarabiDate(defaultVal);
			} catch (ParseException ex) {
				Logger.getLogger(ChatBean.class.getName()).log(Level.SEVERE, null, ex);
				throw new CarabiException("Illegal defailt date: " + defaultVal);
			}
		}
		return date;
	}
	
	/**
	Вывод списка пользователей в формате, используемом для хранимых запросов.
	Добавление информации о непрочитанных сообщениях и онлайне
	 * @param usersList
	 * @return 
	 */
	private JsonObject printUsersForOutput(UserLogon client, List<CarabiUser> usersList, Map<Long, Date> userLastContact, boolean addLastMessages) throws CarabiException {
		Set<String> onlineUsers;
		JsonObject unreadMessagesSenders;
		Map<String, UserRelation> userRelations;
		if (!usersList.isEmpty()) {//если список пользователей пустой -- доп. статистику не собираем
			onlineUsers = getOnlineUsers();
			final String unreadMessagesSendersJson = getUnreadMessagesSenders_Internal(client, addLastMessages);
			unreadMessagesSenders = Json.createReader(new StringReader(unreadMessagesSendersJson)).readObject();
			userRelations = getUserRelations(client.getUser(), usersList);
		} else {
			onlineUsers = new HashSet<>();
			unreadMessagesSenders = Json.createObjectBuilder().build();
			userRelations = new HashMap<>();
		}
		
		//формируем вывод
		JsonArrayBuilder headerColumns = Json.createArrayBuilder();
		headerColumns.add(Utls.parametersToJson("LOGIN", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("FIRSTNAME", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("MIDDLENAME", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("LASTNAME", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("EMAIL", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("DEPARTMENT", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("ROLE", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("SCHEMA_NAME", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("SCHEMA_DESCRIPTION", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("ONLINE", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("MESSAGES_UNREAD", "NUMBER"));
		if (addLastMessages) {
			headerColumns.add(Utls.parametersToJson("LAST_MESSAGE_ID", "NUMBER"));
			headerColumns.add(Utls.parametersToJson("LAST_MESSAGE", "VARCHAR2"));
		}
		headerColumns.add(Utls.parametersToJson("RELATIONS", "VARCHAR2"));
		if (userLastContact != null) {
			headerColumns.add(Utls.parametersToJson("LAST_CONTACT_DATE", "DATE"));
			headerColumns.add(Utls.parametersToJson("LAST_CONTACT_DATE_STR", "VARCHAR2"));
		}
		headerColumns.add(Utls.parametersToJson("LAST_ONLINE_ACTIVE", "DATE"));
		headerColumns.add(Utls.parametersToJson("LAST_ONLINE_ACTIVE_STR", "VARCHAR2"));
		JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("columns", headerColumns);
		JsonArrayBuilder rows = Json.createArrayBuilder();
		for (CarabiUser user: usersList) {
			if (user.equals(client.getUser())) {
				continue;
			}
			JsonArrayBuilder userJson = Json.createArrayBuilder();
			final String login = user.getLogin();
			Utls.addJsonObject(userJson, login);
			Utls.addJsonObject(userJson, user.getFirstname());
			Utls.addJsonObject(userJson, user.getMiddlename());
			Utls.addJsonObject(userJson, user.getLastname());
			Utls.addJsonObject(userJson, user.getEmail());
			Utls.addJsonObject(userJson, user.getCarabiDepartment());
			Utls.addJsonObject(userJson, user.getCarabiRole());
			if (user.getDefaultSchema() != null) {
				Utls.addJsonObject(userJson, user.getDefaultSchema().getName());//SCHEMA_NAME
				Utls.addJsonObject(userJson, user.getDefaultSchema().getDescription());//SCHEMA_DESCRIPTION
			} else {
				userJson.addNull();//SCHEMA_NAME
				userJson.addNull();//SCHEMA_DESCRIPTION
			}
			if (user.showOnline() && onlineUsers.contains(user.getLogin())) {
				userJson.add("1");//ONLINE
			} else {
				userJson.add("0");//ONLINE
			}
			if (addLastMessages) {
				JsonObject unreadMessages = unreadMessagesSenders.getJsonObject(login);
				if (unreadMessages == null) {
					userJson.add("0");//MESSAGES_UNREAD
					userJson.add("-1");//LAST_MESSAGE_ID
					userJson.add("");//LAST_MESSAGE
				} else {
					userJson.add(unreadMessages.get("messages").toString());//MESSAGES_UNREAD
					userJson.add(unreadMessages.get("lastMessageId").toString());//LAST_MESSAGE_ID
					userJson.add(unreadMessages.getString("lastMessage"));//LAST_MESSAGE
				}
			} else {
				JsonValue unreadMessages = unreadMessagesSenders.get(login);
				if (unreadMessages == null) {
					userJson.add("0");//MESSAGES_UNREAD
				} else {
					userJson.add(unreadMessages.toString());//MESSAGES_UNREAD
				}
			}
			UserRelation relation = userRelations.get(login);
			if (relation == null) {
				userJson.addNull();//RELATIONS
			} else {
				Collection<UserRelationType> relationTypes = relation.getRelationTypes();
				StringBuilder relations = new StringBuilder();
				int r = 0;
				for (UserRelationType relationType: relationTypes) {
					if (r > 0) {
						relations.append(";");
					}
					relations.append(relationType.getSysname());
					r++;
				}
				userJson.add(relations.toString());//RELATIONS
			}
			if (userLastContact != null) {
				Utls.addJsonDate(userJson, userLastContact.get(user.getId()), CarabiDate.pattern);//LAST_CONTACT_DATE
				Utls.addJsonDate(userJson, userLastContact.get(user.getId()), CarabiDate.patternShort);//LAST_CONTACT_DATE
			}
			Utls.addJsonDate(userJson, user.getLastActive(), CarabiDate.pattern);//LAST_ONLINE_ACTIVE
			Utls.addJsonDate(userJson, user.getLastActive(), CarabiDate.patternShort);//LAST_ONLINE_ACTIVE_STR
			rows.add(userJson);
		}
		result.add("list", rows);
		return result.build();
	}
	
	private Set<String> onlineUsers;
	private Date lastOnlineUsersGot = new Date(0);
	private Set<String> getOnlineUsers() {
		if ((new Date().getTime() - lastOnlineUsersGot.getTime()) > CarabiFunc.onlineControlTimeout * 1000) {
			onlineUsers = scanOnlineUsers();
			lastOnlineUsersGot = new Date();
		}
		return onlineUsers;
	}
	
	private synchronized Set<String> scanOnlineUsers() {
		//С каждого Eventer пытаемся получить список подключенных пользователей
		TypedQuery<CarabiAppServer> getSevers = emKernel.createNamedQuery("getAllServers", CarabiAppServer.class);
		List<CarabiAppServer> servers = getSevers.getResultList();
		Set<String> newOnlineUsers = new ConcurrentSkipListSet<>();
		for (CarabiAppServer server: servers) {
			try {
				String usersOnlineJson = eventer.eventerSingleRequestResponse(server, "[]", new Holder<>(CarabiEventType.userOnlineQuery.getCode()), true);
				if (usersOnlineJson != null) {
					logger.log(Level.FINE, "online from {0}: {1}", new Object[]{server.getComputer(), usersOnlineJson});
					JsonReader reader = Json.createReader(new StringReader(usersOnlineJson));
					JsonObject usersOnline = reader.readObject();
					newOnlineUsers.addAll(usersOnline.keySet());
				}
			} catch (IOException ex) {
				Logger.getLogger(ChatBean.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return newOnlineUsers;
	}
	
	/**
	 * Из списка пользователей выбирает имеющих связи с данным
	 * @param user произмвольный пользователь
	 * @param usersList пользователи, из которых выбираем привязанных
	 * @return карта "логин привязанного пользователя"=>"объект со связкой"
	 */
	private Map<String, UserRelation> getUserRelations(CarabiUser user, List<CarabiUser> usersList) {
		Map<String, UserRelation> userRelations = new HashMap<>();
		TypedQuery<UserRelation> findUsersRelations = emKernel.createNamedQuery("findUsersRelations", UserRelation.class);
		findUsersRelations.setParameter("mainUser", user);
		findUsersRelations.setParameter("relatedUsers", usersList);
		List<UserRelation> userRelationsList = findUsersRelations.getResultList();
		for (UserRelation userRelation: userRelationsList) {
			userRelations.put(userRelation.getRelatedUser().getLogin(), userRelation);
		}
		return userRelations;
	}
	
	public String getDialog(UserLogon client, CarabiUser interlocutor, String afterDateStr, String search, int crop) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = client.getUser().getMainServer();
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetDialogSoap(targetServer, client.getToken(), interlocutor, afterDateStr, search, crop);
		}
		//получаем сообщения
		CarabiDate afterDate = parceDate(afterDateStr, "01.01.1970");
		final Long userId = client.getUser().getId();
		final TypedQuery<ChatMessage> getDialog;
		if (search != null && !search.equals("")) {
			getDialog = emChat.createNamedQuery("searchInDialog", ChatMessage.class);
			getDialog.setParameter("search", "%" + search.toUpperCase() + "%");
		} else {
			getDialog = emChat.createNamedQuery("getDialog", ChatMessage.class);
		}
		getDialog.setParameter("user", userId);
		getDialog.setParameter("recently", afterDate);
		getDialog.setParameter("interlocutor", interlocutor.getId());
		List<ChatMessage> dialog = getDialog.getResultList();
		//формируем вывод
		JsonArrayBuilder headerColumns = Json.createArrayBuilder();
		headerColumns.add(Utls.parametersToJson("MESSAGE_ID", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("SENDER", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("RECEIVER", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("MESSAGE_TEXT", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("MESSAGE_IS_FULL", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("ATTACHMENT", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("SENT", "DATE"));
		headerColumns.add(Utls.parametersToJson("RECEIVED", "DATE"));
		headerColumns.add(Utls.parametersToJson("EXTENSION_TYPE", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("EXTENSION_VALUE", "VARCHAR2"));
		JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("columns", headerColumns);
		JsonArrayBuilder rows = Json.createArrayBuilder();
		for (ChatMessage message: dialog) {
			JsonArrayBuilder messageJson = Json.createArrayBuilder();
			Utls.addJsonObject(messageJson, message.getId().toString());//MESSAGE_ID
			assert message.getOwnerId().equals(userId);
			if (userId.equals(message.getReceiverId())) {
				Utls.addJsonObject(messageJson, interlocutor.getLogin());//SENDER
				Utls.addJsonObject(messageJson, client.getUser().getLogin());//RECEIVER
			} else if (userId.equals(message.getSenderId())) {
				Utls.addJsonObject(messageJson, client.getUser().getLogin());//SENDER
				Utls.addJsonObject(messageJson, interlocutor.getLogin());//RECEIVER
			} else {
				logger.log(Level.WARNING, "message {0} do not have current user ({1}) as sender or receiver", new Object[]{message.getId(), userId});
				continue;
			}
			String messageText = message.getMessageText();
			if (crop > 0 && messageText.length() > crop) {
				Utls.addJsonObject(messageJson, messageText.substring(0, crop));//MESSAGE_TEXT
				messageJson.add("0");//MESSAGE_IS_FULL
			} else {
				Utls.addJsonObject(messageJson, messageText);//MESSAGE_TEXT
				messageJson.add("1");//MESSAGE_IS_FULL
			}
			Utls.addJsonObject(messageJson, message.getAttachment() == null ? "0" : "1");//ATTACHMENT
			Utls.addJsonObject(messageJson, CarabiDate.wrap(message.getSent()));//SENT
			Utls.addJsonObject(messageJson, CarabiDate.wrap(message.getReceived()));//RECEIVED
			Utls.addJsonObject(messageJson, getMessageExtensionType(message));//EXTENSION_TYPE
			Utls.addJsonObject(messageJson, message.getExtensionValue());//EXTENSION_VALUE
			rows.add(messageJson);
		}
		result.add("list", rows);
		return result.build().toString();
	}
	
	/**
	 * получение sysname типа расширения для сообщения чата.
	 * @return sysname типа расширения. null, если расширения нет.
	 */
	private String getMessageExtensionType(ChatMessage message) {
		final Integer extensionTypeId = message.getExtensionTypeId();
		if (extensionTypeId == null) {
			return null;
		}
		final ChatExtendedMessageType extensionType = emKernel.find(ChatExtendedMessageType.class, extensionTypeId);
		if (extensionType == null) {
			return null;
		} else {
			return extensionType.getSysname();
		}
	}
	public int deleteMessages(UserLogon client, String messagesList) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callDeleteMessagesSoap(targetServer, client.getToken(), messagesList);
		}
		List<Long> idList = parseMessagesIdList(messagesList);
		
		//Получаем вложения удаляемых сообщений
		TypedQuery<FileOnServer> getUserMessagesAttachments = emChat.createNamedQuery("getUserMessagesAttachments", FileOnServer.class);
		getUserMessagesAttachments.setParameter("user", client.getUser().getId());
		getUserMessagesAttachments.setParameter("idlist", idList);
		List<FileOnServer> userMessagesAttachments = getUserMessagesAttachments.getResultList();
		//Удаляем сообщения
		Query deleteMessagesList = emChat.createNamedQuery("deleteMessagesList");
		deleteMessagesList.setParameter("user", client.getUser().getId());
		deleteMessagesList.setParameter("idlist", idList);
		int deletedSize = deleteMessagesList.executeUpdate();
		//Смотрим, какие из вложений более не используются
		//(некоторые могут присутствовать в сообщении собеседника)
		//удаляем их и файлы
		for(FileOnServer attachment: userMessagesAttachments) {
			Query getMessagesWithAttachment = emChat.createNamedQuery("getMessagesWithAttachment");
			getMessagesWithAttachment.setParameter("attachment_id", attachment.getId());
			List messagesWithAttachment = getMessagesWithAttachment.getResultList();
			if (messagesWithAttachment.size() > 0) {
				continue;
			}
			new File(attachment.getContentAddress()).delete();
			emChat.remove(attachment);
		}
		//Отправляем событие клиентам
		try {
			eventer.fireEvent("", client.getUser().getLogin(), CarabiEventType.chatMessageRemove.getCode(), messagesList);
		} catch (IOException ex) {
			Logger.getLogger(ChatBean.class.getName()).log(Level.SEVERE, null, ex);
		}
		return deletedSize;
	}
	
	/**
	 * Возвращает вложение из сообщения, при его наличии
	 * @param client клиентская сессия
	 * @param messageId id сообщения с вложением
	 * @return вложение из сообщения, null при его отсутствии
	 * @throws ru.carabi.server.CarabiException  если сообщение не найдено или не принадлежит пользователю
	 */
	public FileOnServer getMessageAttachement(UserLogon client, Long messageId) throws CarabiException {
		//При необходимости переходим на сервер клиента
		CarabiAppServer targetServer = getTargetUserServer(client.getUser());
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callGetMessageAttachementSoap(targetServer, client.getToken(), messageId);
		}
		ChatMessage messageWithAttachement = emChat.find(ChatMessage.class, messageId);
		if (messageWithAttachement == null) {
			RestException restException = new RestException("message " + messageId + " not found", Response.Status.NOT_FOUND);
			throw new CarabiException(restException);
		}
		if (!client.getUser().getId().equals(messageWithAttachement.getOwnerId())) {
			RestException restException = new RestException("message " + messageId + " does not belong to user " + client.getUser().getId(), Response.Status.CONFLICT);
			throw new CarabiException(restException);
		}
		return messageWithAttachement.getAttachment();
	}
	
	/**
	 * Создание нового объекта {@link FileOnServer} под аттач и сохранение его в базе для генерации ключа.
	 * @param userFilename пользовательское имя файла
	 * @return объект FileOnServer со сгенерированным ID
	 */
	public FileOnServer createAttachment(String userFilename) {
		FileOnServer newAttachment = new FileOnServer();
		newAttachment.setName(userFilename);
		newAttachment = emChat.merge(newAttachment);
		emChat.flush();
		newAttachment.setContentAddress(Settings.CHAT_ATTACHMENTS_LOCATION + "/" + newAttachment.getId() + "_" + userFilename);
		return newAttachment;
	}
	
	/**
	 * Пересохранение объекта ChatAttachment в базе.
	 */
	public FileOnServer updateAttachment(FileOnServer attachment) {
		return emChat.merge(attachment);
	}
	
	public void fireUserState(UserLogon logon, boolean online) throws IOException, CarabiException {
		if (online) {
			//При подключении передаём событие всегда.
			eventer.fireEvent("", "", CarabiEventType.userOnlineEvent.getCode(), "{\"login\":\"" + logon.getUser().getLogin() + "\",\"online\":true}");
		} else {
			//При отключении проверяем, что других сессий этого пользователя нет
			TypedQuery<CarabiAppServer> getSevers = emKernel.createNamedQuery("getAllServers", CarabiAppServer.class);
			List<CarabiAppServer> servers = getSevers.getResultList();
			//На каждом Eventer проверяем наличие данного пользователя
			boolean stillOnline = false;
			for (CarabiAppServer server: servers) {
				try {
					String userOnlineJson = eventer.eventerSingleRequestResponse(server, "[\"" + logon.getUser().getLogin() + "\"]", new Holder<>(CarabiEventType.userOnlineQuery.getCode()), true);
					logger.log(Level.INFO, "online from {0}: {1}", new Object[]{server.getComputer(), userOnlineJson});
					if (userOnlineJson == null) {
						userOnlineJson = "{\"" + logon.getUser().getLogin() + "\":false}";
					}
					JsonReader reader = Json.createReader(new StringReader(userOnlineJson));
					JsonObject userOnline = reader.readObject();
					if (userOnline.getBoolean(logon.getUser().getLogin())) {
						stillOnline = true;
						break;
					}
				} catch (IOException ex) {
					Logger.getLogger(ChatBean.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
			if (!stillOnline) {
				eventer.fireEvent("", "", CarabiEventType.userOnlineEvent.getCode(), "{\"login\":\"" + logon.getUser().getLogin() + "\",\"online\":false}");
			}
		}
	}
	
	public MessagesGroup findOrCreateMessagesGroup(UserLogon logon, String sysname, boolean createIfNotFound) {
		TypedQuery<MessagesGroup> findMessageGroup = emKernel.createNamedQuery("findMessageGroupBySysname", MessagesGroup.class);
		findMessageGroup.setParameter("sysname", sysname);
		List<MessagesGroup> resultList = findMessageGroup.getResultList();
		MessagesGroup group = null;
		if (!resultList.isEmpty()) {
			group = resultList.get(0);
		} else if (createIfNotFound){
			group = createMessagesGroup(logon, sysname, sysname, null, Settings.getCurrentServer());
		}
		return group;
	}
	
	public MessagesGroup createMessagesGroup(UserLogon logon, String name, String sysname, String description, CarabiAppServer server) {
		MessagesGroup group = new MessagesGroup();
		group.setName(name);
		group.setSysname(sysname);
		group.setDescription(description);
		group.setServer(server);
		return emKernel.merge(group);
	}
	
	/**
	 * Отправка сообщения в групповой чат.
	 * @param logon сессия пользователя
	 * @param messagesGroup группа сообщений (групповой чат)
	 * @param messageText сообщение
	 */
	public void writeToMessageGroup(UserLogon logon, MessagesGroup messagesGroup, String messageText) throws CarabiException {
		//При необходимости переходим на сервер группы
		CarabiAppServer targetServer = messagesGroup.getServer();
		if (!Settings.getCurrentServer().equals(targetServer)) {
			callWriteToMessageGroupSoap(targetServer, logon.getToken(), messagesGroup.getSysname(), messageText);
			return;
		}
		ChatMessage message = new ChatMessage();
		message.setMessageText(messageText);
		message.setSenderId(logon.getUser().getId());
		message.setReceiverId(logon.getUser().getId());//Сам с собою веду беседу :D Штатный клиент чата этого не покажет,
		message.setOwnerId(logon.getUser().getId());//а переделывать not null в таблице пока не будем
		message.setSent(new Date());
		message.setExtensionTypeId(getExtensionTypeId("MESSAGES_GROUP", logon));
		message.setExtensionValue(messagesGroup.getSysname());
		emChat.merge(message);
	}
	
	public String readMessagesGroup(UserLogon logon, MessagesGroup messagesGroup, String afterDateStr, String search, int crop) throws CarabiException {
		//При необходимости переходим на сервер группы
		CarabiAppServer targetServer = messagesGroup.getServer();
		if (!Settings.getCurrentServer().equals(targetServer)) {
			return callReadMessagesGroupSoap(targetServer, logon.getToken(), messagesGroup.getSysname(), afterDateStr, search, crop);
		}
		//получаем сообщения
		CarabiDate afterDate = parceDate(afterDateStr, "01.01.1970");
		final Long userId = logon.getUser().getId();
		final TypedQuery<ChatMessage> getDialog;
		if (search != null && !search.equals("")) {
			getDialog = emChat.createNamedQuery("searchInMessagesGroup", ChatMessage.class);
			getDialog.setParameter("search", "%" + search.toUpperCase() + "%");
		} else {
			getDialog = emChat.createNamedQuery("getMessagesGroup", ChatMessage.class);
		}
		getDialog.setParameter("extensionTypeIsGroup", getExtensionTypeId("MESSAGES_GROUP", logon));
		getDialog.setParameter("messagesGroupSysname", messagesGroup.getSysname());
		
		getDialog.setParameter("recently", afterDate);
		List<ChatMessage> dialog = getDialog.getResultList();
		//формируем вывод
		JsonArrayBuilder headerColumns = Json.createArrayBuilder();
		headerColumns.add(Utls.parametersToJson("MESSAGE_ID", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("SENDER", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("RECEIVER", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("MESSAGE_TEXT", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("MESSAGE_IS_FULL", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("ATTACHMENT", "NUMBER"));
		headerColumns.add(Utls.parametersToJson("SENT", "DATE"));
		headerColumns.add(Utls.parametersToJson("RECEIVED", "DATE"));
		headerColumns.add(Utls.parametersToJson("EXTENSION_TYPE", "VARCHAR2"));
		headerColumns.add(Utls.parametersToJson("EXTENSION_VALUE", "VARCHAR2"));
		JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("columns", headerColumns);
		JsonArrayBuilder rows = Json.createArrayBuilder();
		Map<Long, CarabiUser> senders = new HashMap<>();//отправителей много, придётся каждого брать из базы
		for (ChatMessage message: dialog) {
			JsonArrayBuilder messageJson = Json.createArrayBuilder();
			Utls.addJsonObject(messageJson, message.getId().toString());//MESSAGE_ID
			assert message.getOwnerId().equals(userId);
			Long senderId = message.getSenderId();
			CarabiUser sender = senders.get(senderId);
			if (sender == null) {
				sender = emKernel.find(CarabiUser.class, senderId);
				senders.put(senderId, sender);
			}
			Utls.addJsonObject(messageJson, sender.getLogin());//SENDER
			Utls.addJsonObject(messageJson, logon.getUser().getLogin());//RECEIVER
			String messageText = message.getMessageText();
			if (crop > 0 && messageText.length() > crop) {
				Utls.addJsonObject(messageJson, messageText.substring(0, crop));//MESSAGE_TEXT
				messageJson.add("0");//MESSAGE_IS_FULL
			} else {
				Utls.addJsonObject(messageJson, messageText);//MESSAGE_TEXT
				messageJson.add("1");//MESSAGE_IS_FULL
			}
			Utls.addJsonObject(messageJson, message.getAttachment() == null ? "0" : "1");//ATTACHMENT
			Utls.addJsonObject(messageJson, CarabiDate.wrap(message.getSent()));//SENT
			Utls.addJsonObject(messageJson, CarabiDate.wrap(message.getReceived()));//RECEIVED
			Utls.addJsonObject(messageJson, getMessageExtensionType(message));//EXTENSION_TYPE
			Utls.addJsonObject(messageJson, message.getExtensionValue());//EXTENSION_VALUE
			rows.add(messageJson);
		}
		result.add("list", rows);
		return result.build().toString();
	}
	
	private Long callForwardMessageSoap(CarabiAppServer receiverServer, CarabiUser sender, CarabiUser receiver, String messageText, Long attachmentId, Integer extensionTypeId, String extensionValue, boolean markRead) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(receiverServer);
			String token = chatServicePort.prepareToForward();
			token = encrypt(token);
			setCookie((BindingProvider)chatServicePort);
			return chatServicePort.forwardMessage(token, sender.getLogin(), receiver.getLogin(), messageText, attachmentId, extensionTypeId, extensionValue, markRead);
		} catch (MalformedURLException | GeneralSecurityException | WebServiceException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: ", ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private Long callPutMessageSoap(CarabiAppServer targetServer, CarabiUser owner, CarabiUser sender, CarabiUser receiver, Long receivedMessageId, Integer receivedMessageServerId, String messageText, Long attachmentId, Integer extensionTypeId, String extensionValue, boolean markRead) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			String token = chatServicePort.prepareToForward();
			token = encrypt(token);
			setCookie((BindingProvider)chatServicePort);
			return chatServicePort.putMessage(token, owner.getLogin(), sender.getLogin(), receiver.getLogin(), receivedMessageId, receivedMessageServerId, messageText, attachmentId, extensionTypeId, extensionValue, markRead);
		} catch (MalformedURLException | GeneralSecurityException | WebServiceException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private void callMarkReadSoap(CarabiAppServer targetServer, String clientToken, String messageList, boolean read) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			chatServicePort.markRead(clientToken, messageList, read);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private int callMarkReadPreviosSoap(CarabiAppServer targetServer, String clientToken, String loginSender, String date) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.markReadPrevios(clientToken, loginSender, date);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private void callMarkSentReceivedSoap(CarabiAppServer targetServer, String loginSender, String loginReceiver, String messagesList) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			String token = chatServicePort.prepareToForward();
			token = encrypt(token);
			setCookie((BindingProvider)chatServicePort);
			chatServicePort.markReceived(token, loginSender, loginReceiver, messagesList);
		} catch (MalformedURLException | GeneralSecurityException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
		
	}
	
	private Long callGetUnreadMessagesCountSoap(CarabiAppServer targetServer, String clientToken) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getUnreadMessagesCount(clientToken);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callGetUnreadMessagesSendersSoap(CarabiAppServer targetServer, String clientToken) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getUnreadMessagesSenders(clientToken);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callGetUnreadMessagesSendersDetailedSoap(CarabiAppServer targetServer, String clientToken) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getUnreadMessagesSendersDetailed(clientToken);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callGetMessageSoap(CarabiAppServer targetServer, String clientToken, Long messageId, boolean read) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getMessage(clientToken, messageId, read);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callGetMessageDetailsSoap(CarabiAppServer targetServer, String clientToken, Long messageId, boolean read, int crop) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getMessageDetails(clientToken, messageId, read, crop);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callGetLastInterlocutorsSoap(CarabiAppServer targetServer, String clientToken, int size, String afterDate, String search) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getLastInterlocutors(clientToken, size, afterDate, search);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callGetDialogSoap(CarabiAppServer targetServer, String clientToken, CarabiUser interlocutor, String afterDate, String search, int crop) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.getDialog(clientToken, interlocutor.getLogin(), afterDate, search, crop);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private int callDeleteMessagesSoap(CarabiAppServer targetServer, String clientToken, String messagesList) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.deleteMessages(clientToken, messagesList);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private FileOnServer callGetMessageAttachementSoap(CarabiAppServer targetServer, String clientToken, Long messageId) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			ru.carabi.stub.FileOnServer messageAttachementSoap = chatServicePort.getMessageAttachement(CarabiFunc.encrypt(clientToken), messageId);
			FileOnServer messageAttachement = new FileOnServer();
			messageAttachement.setAllFromStub(messageAttachementSoap);
			return messageAttachement;
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		} catch (GeneralSecurityException ex) {
			logger.log(Level.SEVERE, "Encrypting error", ex);
			throw new CarabiException("Encrypting error");
		}
	}
	
	private void callWriteToMessageGroupSoap(CarabiAppServer targetServer, String token, String messagesGroupSysname, String messageText) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			chatServicePort.writeToMessageGroup(token, messagesGroupSysname, messageText);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private String callReadMessagesGroupSoap(CarabiAppServer targetServer, String token, String messagesGroupSysname, String afterDateStr, String search, int crop) throws CarabiException {
		try {
			ChatService chatServicePort = getChatServicePort(targetServer);
			return chatServicePort.readMessagesGroup(token, messagesGroupSysname, afterDateStr, search, crop);
		} catch (MalformedURLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException("Error on connecting to remote server: " + ex.getMessage(), ex);
		} catch (CarabiException_Exception ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiException(ex);
		}
	}
	
	private ChatService chatServicePort;
	private ChatService getChatServicePort(CarabiAppServer targetServer) throws MalformedURLException {
		if (chatServicePort != null) {
			return chatServicePort;
		}
		StringBuilder url = new StringBuilder("http://");
		url.append(targetServer.getComputer());
		url.append(":");
		url.append(targetServer.getGlassfishPort());
		url.append("/");
		url.append(targetServer.getContextroot());
		url.append("/ChatService?wsdl");
		ChatService_Service chatService = new ChatService_Service(new URL(url.toString()));
		chatServicePort = chatService.getChatServicePort();
		return chatServicePort;
	}
	
	/**
	 * Рассылка сообщения списку получателей. Используется функция {@link #sendMessage(ru.carabi.server.entities.CarabiUser, ru.carabi.server.entities.CarabiUser, java.lang.String, java.lang.Long, java.lang.Long)}
	 * @param sender отправитель
	 * @param receiversArray массив логинов получателей
	 * @param messageText текст сообщения
	 * @param extensionTypeId ID типа расширения, если есть
	 * @param extensionValue расширение, если есть
	 * @param markRead сразу помечать сообщение прочитанным
	 * @return массив ID отправленных сообщений
	 * @throws CarabiException 
	 */
	public Long[] sendToReceivers(CarabiUser sender, String[] receiversArray, String messageText, Integer extensionTypeId, String extensionValue, boolean markRead) throws CarabiException {
		Long[] sentMessagesId = new Long[receiversArray.length];
		int i = 0;
		for (String login: receiversArray) {
			CarabiUser receiver = uc.findUser(login);
			sentMessagesId[i] = sendMessage(sender, receiver, messageText, null, null, extensionTypeId, extensionValue, markRead);
			i++;
		}
		return sentMessagesId;
	}

	private List<Long> parseMessagesIdList(String messagesIdList) throws CarabiException {
		long id = -1L;
		//проверка, содержит ли параметр одно число
		try {
			id = Long.parseLong(messagesIdList);
		} catch (NumberFormatException e){}
		List<Long> idList;
		if (id == -1L) {//если нет -- парсинг массива
			try {
				JsonArray idArrayJson = Json.createReader(new StringReader(messagesIdList)).readArray();
				idList = new ArrayList<>(idArrayJson.size());
				for (Iterator<JsonValue> iterator = idArrayJson.iterator(); iterator.hasNext();) {
					JsonValue value = iterator.next();
					id = Long.parseLong(value.toString());
					idList.add(id);
				}
			} catch (Exception e) {
				throw new CarabiException("input incorrect (not int, nor array of int found)");
			}
		} else {
			idList = new ArrayList<>(0);
			idList.add(id);
		}
		return idList;
	}
	
	/**
	 * Поиск и создание объекта ChatExtendedMessageType с системным именем, равным параметру extensionType.
	 * Если объект найден -- он возвращается. Если нет -- новый создаётся в том случае, если текущий пользователь
	 * имеет на это право ({@link UsersControllerBean#userHavePermission(ru.carabi.server.UserLogon, java.lang.String) }(logon, "EDIT_CHAT_MESSAGE_TYPES") == true)
	 * @param extensionTypeName искомый тип расширения
	 * @param logon текущая сессия
	 * @return найденный или созданный тип расширения, null, если на вход подан null или пустая строка
	 */
	public ChatExtendedMessageType findOrCreateExtensionType(String extensionTypeName, UserLogon logon) throws CarabiException {
		if (extensionTypeName == null || "".equals(extensionTypeName)) {
			return null;
		}
		Query query = emKernel.createQuery("select CMT from ChatExtendedMessageType CMT where CMT.sysname = :sysname");
		query.setParameter("sysname", extensionTypeName);
		try {
			return (ChatExtendedMessageType) query.getSingleResult();
		} catch (NoResultException e) {
			if (!uc.userHavePermission(logon, "EDIT_CHAT_MESSAGE_TYPES")) {
				throw new PermissionException(logon, "EDIT_CHAT_MESSAGE_TYPES");
			}
			ChatExtendedMessageType extensionType = new ChatExtendedMessageType();
			extensionType.setName(extensionTypeName);
			extensionType.setSysname(extensionTypeName);
			extensionType = emKernel.merge(extensionType);
			emKernel.flush();
			return extensionType;
		}
	}
	
	public Integer getExtensionTypeId(String extensionTypeName, UserLogon logon) throws CarabiException {
		ChatExtendedMessageType extensionType = findOrCreateExtensionType(extensionTypeName, logon);
		if (extensionType == null ) {
			return null;
		} else {
			return extensionType.getId();
		}
	}
}
