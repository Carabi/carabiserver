package ru.carabi.server.soap;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Редактирование пользователем своего профиля
 * @author sasha
 */
@WebService(serviceName = "ProfileService")
public class ProfileService {
	@EJB private UsersControllerBean usersController;
	@EJB private AdminBean admin;
	Logger logger = CarabiLogging.getLogger(ProfileService.class);
	
	@WebMethod(operationName = "getShowOnlineMode")
	public boolean getShowOnlineMode(
			@WebParam(name = "token") String token
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token, false)) {
			return logon.getUser().showOnline();
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	@WebMethod(operationName = "setShowOnlineMode")
	public void setShowOnlineMode(
			@WebParam(name = "token") String token,
			@WebParam(name = "showOnline") boolean showOnline
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token, false)) {
			admin.setShowOnlineMode(logon.getUser(), showOnline);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
}
