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
import ru.carabi.server.PermissionException;
import ru.carabi.server.RegisterException;
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
	 * Имеет ли текущий пользователь указанное право.
	 * @param token токен авторизации
	 * @param permission кодовое имя права
	 * @return имеет ли текущий пользователь указанное право
	 * @throws CarabiException если пользователь не авторизован или право не найдено
	 */
	@WebMethod(operationName = "havePermision")
	public boolean havePermision(
			@WebParam(name = "token") String token,
			@WebParam(name = "permission") String permission
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return logon.havePermission(permission);
		}
	}
	
	/**
	 * Проверка, что текущий пользователь имеет указанное право
	 * @param token токен авторизации
	 * @param permission кодовое имя права
	 * @throws CarabiException если пользователь не авторизован или не имеет данного права или право не найдено
	 */
	@WebMethod(operationName = "assertPermisionAllowed")
	public void assertPermisionAllowed(
			@WebParam(name = "token") String token,
			@WebParam(name = "permission") String permission
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			logon.assertAllowed(permission);
		}
	}
	
	/**
	 * Возвращает список программ и их модулей ({@link SoftwareProduct}),
	 * доступных текущему пользователю в данный момент. Список может зависеть от
	 * того, через какой сервер зашёл пользователь и с какой базой он работает.
	 * При ненулевом значении параметра currentProduct возвращаются только его дочерние модули.
	 * @param token
	 * @param currentProduct
	 * @param showInvisible
	 * @return
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getAvailableProduction")
	public Collection<SoftwareProduct> getAvailableProduction(
			@WebParam(name = "token") String token,
			@WebParam(name = "currentProduct") String currentProduct,
			@WebParam(name = "showInvisible") boolean showInvisible
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			if (StringUtils.isEmpty(currentProduct)) {
				return productionBean.getAvailableProduction(logon, true, showInvisible);
			} else {
				return productionBean.getAvailableProduction(logon, currentProduct, true, showInvisible);
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
	 * @param showInvisible
	 * @return
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getAllowedProduction")
	public Collection<SoftwareProduct> getAllowedProduction(
			@WebParam(name = "token") String token,
			@WebParam(name = "currentProduct") String currentProduct,
			@WebParam(name = "showInvisible") boolean showInvisible
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			if (StringUtils.isEmpty(currentProduct)) {
				return productionBean.getAvailableProduction(logon, false, showInvisible);
			} else {
				return productionBean.getAvailableProduction(logon, currentProduct, false, showInvisible);
			}
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Проверка, имеет ли пользователь право использовать продукт.
	 * Проверка доступности на сервере и БД не производится.
	 * @param token токен авторизации
	 * @param productSysname кодовое имя программы/модуля
	 * @return имеет ли пользователь право использовать продукт
	 * @throws CarabiException если пользователь не авторизован или продукт не найден
	 */
	@WebMethod(operationName = "productionIsAllowed")
	public boolean productionIsAllowed(
			@WebParam(name = "token") String token,
			@WebParam(name = "productSysname") String productSysname
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return productionBean.productionIsAllowed(logon, productSysname);
		}
	}
	
	/**
	 * Проверка, что пользователь имеет право использовать продукт.
	 * Проверка доступности на сервере и БД не производится.
	 * @param token токен авторизации
	 * @param productSysname кодовое имя программы/модуля
	 * @throws CarabiException если пользователь не авторизован или имеет право использовать продукт или продукт не найден
	 */
	@WebMethod(operationName = "assertProductionAllowed")
	public void assertProductionAllowed(
			@WebParam(name = "token") String token,
			@WebParam(name = "productSysname") String productSysname
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			if (!productionBean.productionIsAllowed(logon, productSysname)) {
				throw new PermissionException(logon, "to use production " + productSysname);
			}
		}
	}
	
	/**
	 * Проверка, доступен ли пользователю продукт.
	 * Производится проверка наличия права, затем доступность на текущем сервере и БД.
	 * @param token токен авторизации
	 * @param productSysname кодовое имя программы/модуля
	 * @return доступен ли пользователю продукт
	 * @throws CarabiException если пользователь не авторизован или продукт не найден
	 */
	@WebMethod(operationName = "productionIsAvailable")
	public boolean productionIsAvailable(
			@WebParam(name = "token") String token,
			@WebParam(name = "productSysname") String productSysname
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return productionBean.productionIsAvailable(logon, productSysname);
		}
	}
	
	/**
	 * Проверка, доступен ли пользователю продукт.
	 * Производится проверка наличия права, затем доступность на текущем сервере и БД.
	 * @param token токен авторизации
	 * @param productSysname кодовое имя программы/модуля
	 * @throws CarabiException если пользователь не авторизован или продукт не доступен, либо не найден
	 */
	@WebMethod(operationName = "assertProductionAvailable")
	public void assertProductionAvailable(
			@WebParam(name = "token") String token,
			@WebParam(name = "productSysname") String productSysname
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			if (!productionBean.productionIsAvailable(logon, productSysname)) {
				throw new PermissionException(logon, "to use production " + productSysname);
			}
		}
	}
}
