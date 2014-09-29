package ru.carabi.server;

import javax.ejb.ApplicationException;

/**
 * Исключение, содержащее ошибку из Oracle
 * @author sasha
 */
@ApplicationException
public class OracleConnectionError extends CarabiException {
	public String schemaName;
	public int errorCode;

	public OracleConnectionError(String message) {
		super(message);
	}

	public OracleConnectionError(String message, int errorCode) {
		super(message);
		this.errorCode = errorCode;
	}
	
	public OracleConnectionError(Throwable cause) {
		super(cause);
	}
	
	public OracleConnectionError(Throwable cause, int errorCode) {
		super(cause);
		this.errorCode = errorCode;
	}
	
	public OracleConnectionError(String message, Throwable cause) {
		super(message, cause);
	}	
}
