package ru.carabi.server.soap;

import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.Permission;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.ProductionBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.UsersPercistenceBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Использование и редактирование пользователем своего профиля
 * @author sasha <kopilov.ad@gmail.com>
 */
@WebService(serviceName = "ProfileService")
public class ProfileService {
	@EJB private UsersControllerBean usersController;
	@EJB private UsersPercistenceBean usersPercistence;
	@EJB private ProductionBean productionBean;
	@EJB private AdminBean admin;
	Logger logger = CarabiLogging.getLogger(ProfileService.class);
	
	@WebMethod(operationName = "getShowOnlineMode")
	public boolean getShowOnlineMode(
			@WebParam(name = "token") String token
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.setShowOnlineMode(logon, logon.getUser(), showOnline);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	@WebMethod(operationName = "getPermissions")
	public Collection<Permission> getPermissions(
			@WebParam(name = "token") String token
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return usersPercistence.getUserPermissions(logon);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	@WebMethod(operationName = "getAvailableProduction")
	public Collection<SoftwareProduct> getAvailableProduction(
			@WebParam(name = "token") String token, 
			@WebParam(name = "currentProduct") String currentProduct
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			if (StringUtils.isEmpty(currentProduct)) {
				return productionBean.getAvailableProduction(logon, true);
			} else {
				return productionBean.getAvailableProduction(logon, currentProduct, true);
			}
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	@WebMethod(operationName = "getAllowedProduction")
	public Collection<SoftwareProduct> getAllowedProduction(
			@WebParam(name = "token") String token, 
			@WebParam(name = "currentProduct") String currentProduct
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			if (StringUtils.isEmpty(currentProduct)) {
				return productionBean.getAvailableProduction(logon, false);
			} else {
				return productionBean.getAvailableProduction(logon, currentProduct, false);
			}
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
}
