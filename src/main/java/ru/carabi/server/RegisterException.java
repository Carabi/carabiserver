package ru.carabi.server;

import javax.ejb.ApplicationException;

/**
 * Исключение, выбрасываемое при ошибке авторизации
 * @author sasha
 */
@ApplicationException
public class RegisterException extends CarabiException {
	private MessageCode messageCode;
	
	public RegisterException(MessageCode messageCode) {
		super(messageCode.toString());
		this.messageCode = messageCode;
	}

	public boolean badLoginPassword() {
		return MessageCode.ILLEGAL_LOGIN_OR_PASSWORD.equals(messageCode) ||
				MessageCode.NO_LOGIN_DERBY.equals(messageCode) ||
				MessageCode.NO_LOGIN_ORACLE.equals(messageCode) ||
				MessageCode.BAD_PASSWORD_DERBY.equals(messageCode) ||
				MessageCode.BAD_PASSWORD_ORACLE.equals(messageCode);
	}
	
	public enum MessageCode {
		INTERNAL_ERROR,
		ORACLE_ERROR,
		ILLEGAL_LOGIN_OR_PASSWORD,
		NO_LOGIN_DERBY,
		NO_LOGIN_ORACLE,
		BAD_PASSWORD_DERBY,
		BAD_PASSWORD_ORACLE,
		NO_WEBUSER,
		NO_SCHEMA,
		NO_TOKEN,
		VERSION_MISMATCH
	}
	
	public MessageCode getMessageCode() {
		return messageCode;
	}
}
