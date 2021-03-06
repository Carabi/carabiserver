package ru.carabi.server.soap;

import java.io.IOException;
import java.io.Serializable;
import java.security.GeneralSecurityException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.enterprise.context.SessionScoped;
import javax.inject.Inject;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.libs.CarabiFunc;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.MessagesGroup;
import ru.carabi.server.kernel.ChatBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Функции для работы с чатом. Сообщения для чата хранятся в отдельной базе на
 * сервере, с скоторым пользователь работает чаще всего. (По умолчанию выбирается
 * первый из использованных серверов, статистика накапливается в таблице USER_AT_SERVER_ENTER.
 * Автоматический перенос данных на часто используемый сервер пока не предусмотрен.)
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "ChatService")
public class ChatService {
	static final Logger logger = CarabiLogging.getLogger(ChatService.class);
	
	@Inject
	private ServerSessionToken sessionToken;
	
	@EJB private ChatBean chatBean;
	@EJB private UsersControllerBean uc;
	
	/**
	 * Отправка сообщения получателю. Функция находит основной сервер получателя,
	 * вызывает {@link ChatService#forwardMessage(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.Long, java.lang.Integer, java.lang.String) }
	 * и при успешной пересылке &mdash; {@link ChatService#putMessage(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.Long, java.lang.Integer, java.lang.String, java.lang.Long, java.lang.Integer, java.lang.String)}
	 * на основном сервере отправителя.
	 * 
	 * @param tokenSender авторизационный токен отправителя
	 * @param loginReceiver логин получателя или список логинов получателей, разделённых ';'
	 * @param messageText текст сообщения
	 * @param markRead сразу помечать сообщение прочитанным
	 * @return Id сообщения на стороне отправителя
	 * @throws ru.carabi.server.CarabiException при ошибке авторизации
	 */
	@WebMethod(operationName = "sendMessage")
	public String sendMessage(
			@WebParam(name = "tokenSender") String tokenSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "messageText") String messageText,
			@WebParam(name = "markRead") boolean markRead
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(tokenSender)){
			CarabiUser sender = logon.getUser();
			return sendMessage(loginReceiver, sender, messageText, null, null, markRead);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Отправка сообщения с расширением. Аналогично {@link #sendMessage(java.lang.String, java.lang.String, java.lang.String)}
	 * с добавлением полей "тип расширения" и "содержимое расширения"
	 * 
	 * @param tokenSender авторизационный токен отправителя
	 * @param loginReceiver логин получателя или список логинов получателей, разделённых ';'
	 * @param messageText текст сообщения
	 * @param extensionType кодовое имя типа расширения
	 * @param extensionValue содержимое расширения
	 * @param markRead сразу помечать сообщение прочитанным
	 * @return Id сообщения на стороне отправителя
	 * @throws ru.carabi.server.CarabiException при ошибке авторизации
	 */
	@WebMethod(operationName = "sendExtendedMessage")
	public String sendExtendedMessage(
			@WebParam(name = "tokenSender") String tokenSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "messageText") String messageText,
			@WebParam(name = "extensionType") String extensionType,
			@WebParam(name = "extensionValue") String extensionValue,
			@WebParam(name = "markRead") boolean markRead
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(tokenSender)){
			CarabiUser sender = logon.getUser();
			Integer extensionTypeId = chatBean.getExtensionTypeId(extensionType, logon);
			return sendMessage(loginReceiver, sender, messageText, extensionTypeId, extensionValue, markRead);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	@WebMethod(operationName = "replicateMessage")
	public String replicateMessage(
			@WebParam(name = "tokenServer") String tokenServer,
			@WebParam(name = "loginSender") String loginSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "messageText") String messageText,
			@WebParam(name = "markRead") boolean markRead
		) throws CarabiException {
		UserLogon administrator = uc.getUserLogon(tokenServer);
		if (administrator == null || !administrator.isPermanent()) {
			throw new CarabiException("unknown tokenServer");
		}
		CarabiUser sender = uc.findUser(loginSender);
		return sendMessage(loginReceiver, sender, messageText, null, null, markRead);
	}
	
	@WebMethod(operationName = "replicateExtendedMessage")
	public String replicateExtendedMessage(
			@WebParam(name = "tokenServer") String tokenServer,
			@WebParam(name = "loginSender") String loginSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "messageText") String messageText,
			@WebParam(name = "extensionTypeId") String extensionType,
			@WebParam(name = "extensionValue") String extensionValue,
			@WebParam(name = "markRead") boolean markRead
		) throws CarabiException {
		UserLogon administrator = uc.getUserLogon(tokenServer);
		if (administrator == null || !administrator.isPermanent()) {
			throw new CarabiException("unknown tokenServer");
		}
		CarabiUser sender = uc.findUser(loginSender);
		Integer extensionTypeId = chatBean.getExtensionTypeId(extensionType, administrator);
		return sendMessage(loginReceiver, sender, messageText, extensionTypeId, extensionValue, markRead);
	}
	
	private String sendMessage(String loginReceiver,
			CarabiUser sender,
			String messageText,
			Integer extensionTypeId,
			String extensionValue,
			boolean markRead
		) throws CarabiException {
		if (loginReceiver.contains(";")) {
			String[] receiversArray = loginReceiver.split(";");
			Long[] sentMessagesId = chatBean.sendToReceivers(sender, receiversArray, messageText, extensionTypeId, extensionValue, markRead);
			return StringUtils.join(sentMessagesId, ";");
		} else {
			CarabiUser receiver = uc.findUser(loginReceiver);
			return chatBean.sendMessage(sender, receiver, messageText, null, null, extensionTypeId, extensionValue, markRead).toString();
		}
	}
	
	/**
	 * Доставка сообщения получателю (для межсерверного использования). Предполагается, что его база чата располагается
	 * на текущем сервере (если нет &mdash; вызывается данная функция соответствующего
	 * сервера). Сообщение записывается в базу, а пользователь получает уведомление
	 * через Eventer.<br/>
	 * Требуется авторизация программного клиента. Для этого надо вызвать функцию
	 * prepareToForward и зашифровать её результат.
	 * @param softwareToken
	 * @param loginSender
	 * @param loginReceiver
	 * @param messageText
	 * @param attachmentId
	 * @param extensionTypeId
	 * @param extensionValue
	 * @param markRead
	 * @return Id сообщения на стороне получателя
	 * @throws ru.carabi.server.CarabiException Если не удалось найти пользователя по логину или при ошибке авторизации (при многократном обращении -- возможно,
	 * сессия была потеряна, можно вызвать {@link ChatService#prepareToForward()} повторно)
	 */
	@WebMethod(operationName = "forwardMessage")
	public Long forwardMessage(
			@WebParam(name = "softwareToken") String softwareToken,
			@WebParam(name = "loginSender") String loginSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "messageText") String messageText,
			@WebParam(name = "attachmentId") Long attachmentId,
			@WebParam(name = "extensionTypeId") Integer extensionTypeId,
			@WebParam(name = "extensionValue") String extensionValue,
			@WebParam(name = "markRead") boolean markRead
		) throws CarabiException {
		checkSoftwareToken(softwareToken);
		CarabiUser sender = uc.findUser(loginSender);
		CarabiUser receiver = uc.findUser(loginReceiver);
		return chatBean.forwardMessage(sender, receiver, messageText, attachmentId, extensionTypeId, extensionValue, markRead);
	}
	
	/**
	 * Запись сообщения в базу чата (для межсерверного использования).
	 * Требуется авторизация программного клиента. Для этого надо вызвать функцию
	 * prepareToForward и зашифровать её результат.
	 * @param softwareToken
	 * @param loginOwner
	 * @param loginSender
	 * @param loginReceiver
	 * @param receivedMessageId
	 * @param receivedMessageServerId
	 * @param messageText
	 * @param attachmentId
	 * @param extensionTypeId
	 * @param extensionValue
	 * @param markRead
	 * @return Id сохранённого сообщения
	 * @throws ru.carabi.server.CarabiException Если не удалось найти пользователя по логину или при ошибке авторизации (при многократном обращении -- возможно,
	 * сессия была потеряна, можно вызвать {@link ChatService#prepareToForward()} повторно)
	 */
	@WebMethod(operationName = "putMessage")
	public Long putMessage(
			@WebParam(name = "softwareToken") String softwareToken,
			@WebParam(name = "loginOwner") String loginOwner,
			@WebParam(name = "loginSender") String loginSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "receivedMessageId") Long receivedMessageId,
			@WebParam(name = "receivedMessageServerId") Integer receivedMessageServerId,
			@WebParam(name = "messageText") String messageText,
			@WebParam(name = "attachmentId") Long attachmentId,
			@WebParam(name = "extensionTypeId") Integer extensionTypeId,
			@WebParam(name = "extensionValue") String extensionValue,
			@WebParam(name = "markRead") boolean markRead
		) throws CarabiException {
		checkSoftwareToken(softwareToken);
		CarabiUser owner = uc.findUser(loginOwner);
		CarabiUser sender = uc.findUser(loginSender);
		CarabiUser receiver = uc.findUser(loginReceiver);
		return chatBean.putMessage(owner, sender, receiver, receivedMessageId, receivedMessageServerId, messageText, attachmentId, extensionTypeId, extensionValue, markRead);
	}
	
	/**
	 * Получение количества непрочитанных сообщений.
	 * @param token токен пользователя
	 * @return
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getUnreadMessagesCount")
	public Long getUnreadMessagesCount(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getUnreadMessagesCount(logon);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Получение количества непрочитанных сообщений от разных пользователей.
	 * @param token токен пользователя
	 * @return JSON-структура вида {"пользователь":m, "пользователь2":n}
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getUnreadMessagesSenders")
	public String getUnreadMessagesSenders(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getUnreadMessagesSenders(logon);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Получение количества непрочитанных сообщений от разных пользователей.
	 * @param token токен пользователя
	 * @return JSON-структура вида 
	 * <pre>
	 *{
	 *	"пользователь": {
	 *		{"messages":2,"lastMessageId":10,"lastMessage":"test message"}
	 *	},
	 *	"пользователь2": {
	 *		{"messages":5,"lastMessageId":17,"lastMessage":"new message"}
	 *	}
	 * }
	 * </pre
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getUnreadMessagesSendersDetailed")
	public String getUnreadMessagesSendersDetailed(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getUnreadMessagesSendersDetailed(logon);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Прочитать письмо. Возвращает текст сообщения (входящего или отправленного).
	 * Если если read==true, помечает его прочитанным (только для входящих сообщений,
	 * см. {@link #markRead(java.lang.String, java.lang.Long, boolean)})
	 * @param token токен пользователя
	 * @param messageId id сообщения
	 * @param read поставить пометку о прочтении
	 * @return текст сообщения
	 * @throws ru.carabi.server.CarabiException Если пользователь не найден или сообщение не его, либо не найдено
	 */
	@WebMethod(operationName = "getMessage")
	public String getMessage(
			@WebParam(name = "token") String token,
			@WebParam(name = "messageId") Long messageId,
			@WebParam(name = "read") boolean read
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getMessage(logon, messageId, read);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Прочитать письмо. Возвращает сообщение (входящее или отправленное) в виде
	 * JSON-объекта с логинами отправителя/получателя, датой отправки/получения и текстом.
	 * Если если read==true, помечает его прочитанным (только для входящих сообщений,
	 * см. {@link #markRead(java.lang.String, java.lang.Long, boolean)})
	 * @param token токен пользователя
	 * @param messageId id сообщения
	 * @param read поставить пометку о прочтении
	 * @param crop при положительном значении -- обрезаем сообщение до данного числа символов
	 * @return JSON-объект с деталями о сообщении
	 * @throws ru.carabi.server.CarabiException Если пользователь не найден или сообщение не его, либо не найдено
	 */
	@WebMethod(operationName = "getMessageDetails")
	public String getMessageDetails(
			@WebParam(name = "token") String token,
			@WebParam(name = "messageId") Long messageId,
			@WebParam(name = "read") boolean read,
			@WebParam(name = "crop") int crop
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getMessageDetails(logon, messageId, read, crop);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Пометить прочитанность сообщения. Выполняется только для входящих сообщений
	 * (владелец == получатель). При пометке прочитанным в указанном сообщении и
	 * соответствующем ему отправленном выставляется текущая дата в поле RECEIVED.
	 * При пометке непрочитанным поле RECEIVED обнуляется только в указанном сообщении.
	 * @param token Токен пользователя (получателя сообщения, ставящего пометку о прочтении)
	 * @param messagesList id сообщения или массив id в формате JSON
	 * @param read true -- пометить прочитанным и отправить уведомление, false -- пометить непрочитанным.
	 * @return true, при успешном выполнении функции
	 * @throws ru.carabi.server.CarabiException если пользователь не найден, если сообщение не принадлежит пользователю или не найдено
	 */
	@WebMethod(operationName = "markRead")
	public boolean markRead(
			@WebParam(name = "token") String token,
			@WebParam(name = "messagesList") String messagesList,
			@WebParam(name = "read") boolean read
		) throws CarabiException {
		try (UserLogon receiverLogon = uc.tokenAuthorize(token)) {
			chatBean.markRead(receiverLogon, messagesList, read);
			return true;
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Пометить все сообщения прочитанными
	 * @param Токен пользователя (получателя сообщения, ставящего пометки о прочтении)
	 */
	@WebMethod(operationName = "readAllMessages")
	public void readAllMessages(@WebParam(name = "token") String token) {
		
	}
	
	/**
	 * Пометить прочитанность старых сообщений. 
	  * Выподняется функция {@link #markRead(java.lang.String, java.lang.String, boolean) }
	  * для всех сообщений, отправленных до указанной даты.
	 * @param token Токен пользователя (получателя сообщения, ставящего пометки о прочтении)
	 * @param loginSender логин отправителя, письма которого помечаем
	 * @param timestamp Дата и время в формате Carabi. Более старые общения будут помечены прочитанными.
	 * @return число помеченных сообщений
	 * @throws ru.carabi.server.CarabiException если пользователь не найден, если сообщение не принадлежит пользователю или не найдено
	 */
	@WebMethod(operationName = "markReadPrevios")
	public int markReadPrevios(
			@WebParam(name = "token") String token,
			@WebParam(name = "loginSender") String loginSender,
			@WebParam(name = "timestamp") String timestamp
		) throws CarabiException {
		try (UserLogon receiverLogon = uc.tokenAuthorize(token)) {
			CarabiUser sender = uc.findUser(loginSender);
			return chatBean.markReadPrevios(receiverLogon, sender, timestamp);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Установка уведомления о доставке в отправленном сообщении
	 * Выставляется текущая дата в поле RECEIVED.
	 * @param softwareToken
	 * @param loginSender
	 * @param loginReceiver
	 * @param messagesList
	 * @return 
	 * @throws ru.carabi.server.CarabiException 
	 */
	@WebMethod(operationName = "markReceived")
	public boolean markSentReceived(
			@WebParam(name = "softwareToken") String softwareToken,
			@WebParam(name = "loginSender") String loginSender,
			@WebParam(name = "loginReceiver") String loginReceiver,
			@WebParam(name = "messagesList") String messagesList
		) throws CarabiException {
		checkSoftwareToken(softwareToken);
		CarabiUser sender = uc.findUser(loginSender);
		CarabiUser receiver = uc.findUser(loginReceiver);
		chatBean.markSentReceived(sender, receiver, messagesList);
		return true;
	}
	
	/**
	 * Получение полного списка пользователей, доступных для общения.
	 * Возвращает пользователей, имеющих с текущим общее подразделение в качестве основного.
	 * Возможен поиск пользователей единственного подразделения (необходимо иметь к нему доступ).
	 * @param token авторизационный токен клиента
	 * @param department кодовое название подразделения, в котором ищем (null -- искать во всех)
	 * @param search поиск по ФИО, логину, описанию (если пустой &mdash; возвращаются все пользователи)
	 * @return Выборка в формате, используемом при запуске хранимых запросов
	 */
	@WebMethod(operationName = "getContactList")
	public String getContactList(
			@WebParam(name = "token") String token,
			@WebParam(name = "department") String department,
			@WebParam(name = "search") String search
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getContactList(logon, department, search);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Получение списка связанных пользователей.
	 * @param token авторизационный токен клиента
	 * @param relations Json-массив типов связей, которые должен иметь пользователь.
	 * Если пустой / пустая строка -- возвращаются пользователи со всеми связями,
	 * в том числе немаркированными. Если не читается, как Json -- воспринимается, как имя одного типа.
	 * @param conjunction если true -- возвращаются пользователи, имеющие все указанные типы связи,
	 * иначе -- имеющие хотя бы один из указанных типов связи
	 * @return Выборка в формате, используемом при запуске хранимых запросов
	 */
	@WebMethod(operationName = "getRelatedUsersList")
	public String getRelatedUsersList(
			@WebParam(name = "token") String token,
			@WebParam(name = "relations") String relations,
			@WebParam(name = "conjunction") boolean conjunction
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getRelatedUsersList(logon, relations, conjunction);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Получение списка пользователей.
	 * @param token авторизационный токен клиента
	 * @param search поиск по ФИО, логину, описанию (если пустой &mdash; возвращаются все пользователи)
	 * @return Выборка в формате, используемом при запуске хранимых запросов
	 */
	@WebMethod(operationName = "getContact")
	public String getContact(
			@WebParam(name = "token") String token,
			@WebParam(name = "login") String login
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getContact(logon, login);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Получение списка последних собеседников
	 * @param token токен пользователя
	 * @param size максимальный объём выборки (0 &mdash; без ограничений)
	 * @param afterDate дата в формате Carabi. Собеседники, имеющие сообщения новее этой даты, включаются в выборку.
	 * @param search поиск по ФИО, логину, описанию
	 * @return Выборка в формате, используемом при запуске хранимых запросов
	 */
	@WebMethod(operationName = "getLastInterlocutors")
	public String getLastInterlocutors(
			@WebParam(name = "token") String token,
			@WebParam(name = "size") int size,
			@WebParam(name = "afterDate") String afterDate,
			@WebParam(name = "search") String search
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getLastInterlocutors(logon, size, afterDate, search);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Получение диалога (последних сообщений с собеседником)
	 * @param token токен пользователя
	 * @param interlocutor логин собеседника
	 * @param afterDate дата в формате Carabi. Cообщения новее этой даты включаются в выборку.
	 * @param search поиск по тексту и названию вложенных файлов
	 * @param crop gри положительном значении -- обрезаем сообщение до данного числа символов
	 * @return Выборка в формате, используемом при запуске хранимых запросов
	 */
	@WebMethod(operationName = "getDialog")
	public String getDialog(
			@WebParam(name = "token") String token,
			@WebParam(name = "interlocutor") String interlocutor,
			@WebParam(name = "afterDate") String afterDate,
			@WebParam(name = "search") String search,
			@WebParam(name = "crop") int crop
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getDialog(logon, uc.findUser(interlocutor), afterDate, search, crop);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Удаление сообщения или списка сообщений.
	 * @param token токен пользователя
	 * @param messagesList id сообщения или массив id в формате JSON
	 * @return количество удалённых сообщений
	 */
	@WebMethod(operationName = "deleteMessages")
	public int deleteMessages(
			@WebParam(name = "token") String token,
			@WebParam(name = "messagesList") String messagesList
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.deleteMessages(logon, messagesList);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Рассылка уведомлений о состоянии пользователя онлайн/оффлайн.
	 * Отправляет сообщение Eventer-а с кодом 14.
	 * @param token зашифрованный токен пользователя
	 * @param online 
	 * @throws ru.carabi.server.CarabiException 
	 */
	@WebMethod(operationName = "fireUserState")
	public void fireUserState(
			@WebParam(name = "token") String token,
			@WebParam(name = "online") boolean online
		) throws CarabiException {
		try {
			token = CarabiFunc.decrypt(token);
		} catch (GeneralSecurityException ex) {
			Logger.getLogger(ChatService.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiException("token incorrect");
		}
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			chatBean.fireUserState(logon, online);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		} catch (IOException ex) {
			Logger.getLogger(ChatService.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}
	
	/**
	 * Получение данных о вложении (для межсерверного использования).
	 * @param token зашифрованный токен клиента
	 * @param messageId id сообщения с вложением
	 * @return данные о вложении
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getMessageAttachement")
	public FileOnServer getMessageAttachement(
			@WebParam(name = "token") String token,
			@WebParam(name = "messageId") Long messageId) throws CarabiException {
		try {
			token = CarabiFunc.decrypt(token);
		} catch (GeneralSecurityException ex) {
			Logger.getLogger(ChatService.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiException("token incorrect");
		}
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			return chatBean.getMessageAttachement(logon, messageId);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Отправка сообщения в групповой чат.
	 * @param token токен пользователя
	 * @param messagesGroupSysname кодовое имя группового чата
	 * @param messageText сообщение
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "writeToMessageGroup")
	public void writeToMessageGroup(
			@WebParam(name = "token") String token,
			@WebParam(name = "messagesGroupSysname") String messagesGroupSysname,
			@WebParam(name = "messageText") String messageText) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			MessagesGroup messagesGroup = chatBean.findOrCreateMessagesGroup(logon, messagesGroupSysname, true);
			chatBean.writeToMessageGroup(logon, messagesGroup, messageText);
		}
	}
	
	/**
	 * Возвращает сообщения, написанные в групповом чате.
	 * Аналогично {@link #getDialog(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int)},
	 * вместо собеседника -- название группового чата.
	 * @param token
	 * @param messagesGroupSysname 
	 * @param afterDate
	 * @param search
	 * @param crop
	 * @return 
	 */
	public String readMessagesGroup(
			@WebParam(name = "token") String token,
			@WebParam(name = "messagesGroupSysname") String messagesGroupSysname,
			@WebParam(name = "afterDate") String afterDate,
			@WebParam(name = "search") String search,
			@WebParam(name = "crop") int crop) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			MessagesGroup messagesGroup = chatBean.findOrCreateMessagesGroup(logon, messagesGroupSysname, false);
			if (messagesGroup == null) {
				throw new CarabiException(" Messages group " + messagesGroupSysname + "not found");
			}
			return chatBean.readMessagesGroup(logon, messagesGroup, afterDate, search, crop);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	private void checkSoftwareToken(String softwareToken) throws CarabiException {
		try {
			String decrypt = CarabiFunc.decrypt(softwareToken);
			if (!decrypt.equals(sessionToken.getToken())) {
				throw new CarabiException("token incorrect");
			}
		} catch (GeneralSecurityException ex) {
			Logger.getLogger(ChatService.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiException("token incorrect");
		}
	}
	/**
	 * Генерация ключа для межсерверного использования.
	 * @return ключ авторизации, запоминаемый на данном сервере, который клиент
	 * (другой сервер) должен отправить зашифрованным для пересылки почты
	 */
	@WebMethod(operationName = "prepareToForward")
	public String prepareToForward() {
		String token = CarabiFunc.getRandomString(128);
		sessionToken.setToken(token);
		return token;
	}
}

@SessionScoped
class ServerSessionToken implements Serializable {
	private String token;
	
	public String getToken() {
		return token;
	}
	
	public void setToken(String token) {
		this.token = token;
	}
}
