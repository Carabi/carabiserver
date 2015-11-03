package ru.carabi.server.soap;

import java.util.Collection;
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
	private static final Logger logger = CarabiLogging.getLogger(ProfileService.class);
	
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
	
	/**
	 * Возвращает права ({@link Permission}), которые текущий пользователь имеет в системе.
	 * @param token токен авторизации
	 * @return список прав
	 * @throws CarabiException 
	 */
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
	
	/**
	 * Возвращает список программ и их модулей ({@link SoftwareProduct}),
	 * доступных текущему пользователю в данный момент. Список может зависеть от
	 * того, через какой сервер зашёл пользователь и с какой базой он работает.
	 * При ненулевом значении параметра currentProduct возвращаются только его дочерние модули.
	 * @param token
	 * @param currentProduct
	 * @return
	 * @throws CarabiException 
	 */
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
	
	/**
	 * Возвращает список программ и их модулей ({@link SoftwareProduct}),
	 * которыми пользователь имеет право пользоваться. Не зависит от текущего окружения.
	 * При ненулевом значении параметра currentProduct возвращаются только его дочерние модули.
	 * @param token
	 * @param currentProduct
	 * @return
	 * @throws CarabiException 
	 */
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
