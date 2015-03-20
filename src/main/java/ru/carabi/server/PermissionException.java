package ru.carabi.server;

import javax.ejb.ApplicationException;
import ru.carabi.server.entities.CarabiUser;

/**
 * Исключение, выбрасываемое при нарушении прав
 * @author sasha
 */
@ApplicationException
public class PermissionException extends CarabiException {

	public PermissionException(CarabiUser user, String permission) {
		super("User " + user.getLogin() + " does not have permission " + permission);
	}
	
	public PermissionException(UserLogon logon, String permission) {
		this(logon.getUser(), permission);
	}
	
}
