package ru.carabi.server;

import javax.ejb.ApplicationException;

/**
 * Сообщение от Carabi на уровне бизнес-логики, перехватываемое в виде ошибки от Oracle.
 * Клиентом должно обрабатываться, как сообщение пользователю, а не как ошибка.
 * @author sasha
 */
@ApplicationException
public class CarabiOracleMessage extends CarabiException {
	public String schemaName;

	public CarabiOracleMessage(String message) {
		super(message);
	}

	public CarabiOracleMessage(Throwable cause) {
		super(cause);
	}
	
	public CarabiOracleMessage(String message, Throwable cause) {
		super(message, cause);
	}	
}
