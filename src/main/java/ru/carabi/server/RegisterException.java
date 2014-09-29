package ru.carabi.server;

/**
 * Исключение, выбрасываемое при ошибке авторизации
 * @author sasha
 */
public class RegisterException extends CarabiException {
	public RegisterException(MessageCode messageCode) {
		super(messageCode.toString());
	}

	public enum MessageCode {
		INTERNAL_ERROR,
		ORACLE_ERROR,
		NO_LOGIN_DERBY,
		NO_LOGIN_ORACLE,
		BAD_PASSWORD_DERBY,
		BAD_PASSWORD_ORACLE,
		NO_WEBUSER,
		NO_SCHEMA,
		NO_TOKEN,
		VERSION_MISMATCH
	}
	
}
