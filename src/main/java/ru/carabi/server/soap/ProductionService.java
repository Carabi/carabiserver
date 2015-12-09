package ru.carabi.server.soap;

import java.util.List;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.kernel.ProductionBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * Получение и запись сведений о продукции Караби.
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "ProductionService")
public class ProductionService {
	
	@EJB private UsersControllerBean usersController;
	@EJB private ProductionBean productionBean;
	
	/**
	 * Получение сведений о версиях продукта.
	 * Выдаются подробные сведения об имеющихся версиях одного продукта компании Караби по его системному имени.
	 * Версии, для которых не указано подразделение ({@link ru.carabi.server.entities.Department), возвращаются всегда.
	 * Если указан параметр department &mdash; добавляются версии с данным подразделением.
	 * Если department не указан, он берётся от текущего пользователя при условии, что ignoreDepartment==false.
	 * Если showAllDepartments==true, добавляются спец. версии всех подразделений
	 * (параметры department и ignoreDepartment игнорируются).
	 * @param token авторизационный токен
	 * @param productName системное имя продукта
	 * @param department компания или подразделение, которому предназначена версия (по умолчанию &mdash; подразделение текущего пользователя).
	 * @param ignoreDepartment показывать только общие версии (не помеченные, как предназначенные конкретному подразделению)
	 * @param showAllDepartments показывать версии для всех подразделений
	 * @return массив сведений о версиях
	 * @throws CarabiException если продукта с таким именем не существует
	 */
	@WebMethod(operationName = "getVersionsList")
	public List<ProductVersion> getVersionsList(
			@WebParam(name = "token") String token,
			@WebParam(name = "productName") String productName,
			@WebParam(name = "department") String department,
			@WebParam(name = "ignoreDepartment") boolean ignoreDepartment,
			@WebParam(name = "showAllDepartments") boolean showAllDepartments)
	 throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return productionBean.getVersionsList(logon, productName, department, ignoreDepartment, showAllDepartments);
		}
	}
	
	/**
	 * Получение сведений о последней версии продукта.
	 * Выдаются подробные сведения о последней версии одного продукта компании Караби по системному имени.
	 * Учитываются общие версии и версии, предназначенные подразделению текущего пользователя, либо
	 * иного подразделения, указанного в параметре department. Учитываются так же вышестоящие подразделения.
	 * Производится сортировка по номеру версии (свежие в приоритете) и по привязанности
	 * к подразделению (null -- низший приоритет, текущий department -- высший).
	 * Ставрые версии для указанного/нижележащего подразделения имеют приоритет 
	 * над новыми для вышележащего/общими версиями только если в БД указано поле
	 * {@link ProductVersion#doNotAdviceNewerCommon}, иначе общая свежая сборка имеет больший приоритет.
	 * При указании параметра ignoreDepartment версии для подразделений игнорируются.
	 * @param token авторизационный токен
	 * @param productName системное имя продукта
	 * @param department компания или подразделение, которому предназначена версия
	 * @param ignoreDepartment учитвыать только общие версии (не адресованные конкретным подразделениям)
	 * @return сведения о последней версии
	 * @throws CarabiException если продукта с таким именем не существует
	 */
	@WebMethod(operationName = "checkLastVersion")
	public ProductVersion checkLastVersion(
			@WebParam(name = "token") String token,
			@WebParam(name = "productName") String productName,
			@WebParam(name = "department") String department,
			@WebParam(name = "ignoreDepartment") boolean ignoreDepartment 
	) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return productionBean.getLastVersion(logon, productName, department, ignoreDepartment);
		}
	}
	
	/**
	 * Изменить доступ пользователя к продукту.
	 * Возможно создание продукта и права налету (текущий пользователь должен иметь право
	 * ADMINISTRATING-PRODUCTS-EDIT и ADMINISTRATING-PERMISSIONS-EDIT)
	 * @param token авторизационный токен
	 * @param productName системное имя продукта
	 * @param userLogin пользователь
	 * @param isAllowed true -- дать доступ, false -- отобрать доступ.
	 * @param autocreate создать продукт и право на его использование, если их нет.
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "allowForUser")
	public void allowForUser(
			@WebParam(name = "token") String token,
			@WebParam(name = "productName") String productName,
			@WebParam(name = "userLogin") String userLogin,
			@WebParam(name = "isAllowed") boolean isAllowed,
			@WebParam(name = "autocreate") boolean autocreate
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			productionBean.allowForUser(logon, productName, userLogin, isAllowed, autocreate);
		}
	}
}
