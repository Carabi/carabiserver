package ru.carabi.server;

import javax.ejb.ApplicationException;

/**
 *
 * @author sasha
 */
@ApplicationException
public class CarabiException extends Exception {
	public String schemaName;
	public int errorCode;

	public CarabiException(String message) {
		super(message);
	}

	public CarabiException(String message, int errorCode) {
		super(message);
		this.errorCode = errorCode;
	}
	
	public CarabiException(Throwable cause) {
		super(cause);
	}
	
	public CarabiException(Throwable cause, int errorCode) {
		super(cause);
		this.errorCode = errorCode;
	}
	
	public CarabiException(String message, Throwable cause) {
		super(message, cause);
	}	
}
