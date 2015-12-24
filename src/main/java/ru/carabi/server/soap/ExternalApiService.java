package ru.carabi.server.soap;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.sms.SmsSenderBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Функции для связи с другими серверами
 * @author sasha
 */
@WebService(serviceName = "ExternalApiService")
public class ExternalApiService {
	private static final Logger logger = CarabiLogging.getLogger(ExternalApiService.class);
	private @EJB UsersControllerBean usersController;
	private @EJB SmsSenderBean smsSender;
	
//	@WebMethod(operationName = "sendEmail")
//	public void sendEmail (
//		@WebParam(name = "senderToken") String senderToken,
//		@WebParam(name = "receiverLogin") String receiverLogin,
//		@WebParam(name = "subject") String subject,
//		@WebParam(name = "text") String text
//		) throws CarabiException {
//		try (UserLogon logon = usersController.tokenAuthorize(senderToken)) {
//			logger.log(Level.INFO, "email:\nFrom: {0}\nTo: {1}\nSubjcet: {2}\n\n{3}", new Object[]{logon.userLogin(), receiverLogin, subject, text});
//		}
//	}

	/**
	 * Отправить SMS конкретному пользователю.
	 * Телефон получателя ищется в БД. Название или номер отправителя для API SMS-шлюза
	 * может быть задано в конфигурационном файле или параметре sender данного метода.
	 * @param token токен авторизации текущего пользователя
	 * @param receiverLogin логин получателя
	 * @param text текст сообщения
	 * @param sender название или телефон отправителя -- для API SMS-шлюзов
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "sendSms")
	public void sendSms (
		@WebParam(name = "token") String token,
		@WebParam(name = "receiverLogin") String receiverLogin,
		@WebParam(name = "text") String text,
		@WebParam(name = "sender") String sender
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			smsSender.sendSmsMessage(logon, usersController.findUser(receiverLogin), text, sender);
		}
	}
}
