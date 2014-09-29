package ru.carabi.server;

import javax.ejb.ApplicationException;

/**
 * Исключение, содержащее ошибку из Oracle
 * @author sasha
 */
@ApplicationException
public class CarabiOracleError extends CarabiException {
	public String schemaName;
	public int errorCode;

	public CarabiOracleError(String message) {
		super(message);
	}

	public CarabiOracleError(String message, int errorCode) {
		super(message);
		this.errorCode = errorCode;
	}
	
	public CarabiOracleError(Throwable cause) {
		super(cause);
	}
	
	public CarabiOracleError(Throwable cause, int errorCode) {
		super(cause);
		this.errorCode = errorCode;
	}
	
	public CarabiOracleError(String message, Throwable cause) {
		super(message, cause);
	}	
}
