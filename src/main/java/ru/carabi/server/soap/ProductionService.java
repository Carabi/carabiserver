package ru.carabi.server.soap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Получение и запись сведений о продукции Караби.
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "ProductionService")
public class ProductionService {
	private static final Logger logger = CarabiLogging.getLogger(ProductionService.class);
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	@EJB
	private UsersControllerBean usersController;
	
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
		try (UserLogon logon = usersController.tokenAuthorize(token, false)) {
			String sql = "select current_versions.*, department.sysname as department_sysname, department.name as department_name\n"+
					//product_version_id, version_number, issue_date, singularity, download_url, is_significant_update, destinated_for_department, do_not_advice_newer_common
					"from appl_production.search_product_versions(?, ?, ?, ?, ?) as current_versions\n" +
					"left join carabi_kernel.department on department.department_id = current_versions.destinated_for_department";
			Query searchProductVersions = em.createNativeQuery(sql);
			searchProductVersions.setParameter(1, token);
			searchProductVersions.setParameter(2, productName);
			searchProductVersions.setParameter(3, department);
			searchProductVersions.setParameter(4, ignoreDepartment);
			searchProductVersions.setParameter(5, showAllDepartments);
			List<?> resultList = searchProductVersions.getResultList();
			TreeSet<ProductVersion> orderer = new TreeSet(new VersionComparator());
			for (Object row: resultList) {
				Object[] data = (Object[])row;
				ProductVersion productVersion = new ProductVersion();
				productVersion.setId((Long) data[0]);
				productVersion.setVersionNumber((String) data[1]);
				productVersion.setIssueDate((Date) data[2]);
				productVersion.setSingularity((String) data[3]);
				productVersion.setDownloadUrl((String) data[4]);
				productVersion.setIsSignificantUpdate((Boolean) data[5]);
				productVersion.setDoNotAdviceNewerCommon((Boolean) data[7]);
				final Integer departmentId = (Integer) data[6];
				if (departmentId != null) {
					Department departmentObj = new Department();
					departmentObj.setId(departmentId);
					departmentObj.setSysname((String) data[8]);
					departmentObj.setName((String) data[9]);
					productVersion.setDestinatedForDepartment(departmentObj);
				}
				orderer.add(productVersion);
			}
			List<ProductVersion> versions = new ArrayList<>();
			for (ProductVersion version: orderer) {
				versions.add(version);
			}
			return versions;
		}
	}
	
	/**
	 * Получение сведений о последней версии продукта.
	 * Выдаются подробные сведения о последней версии одного продукта компании Караби по системному имени.
	 * Учитываются общие версии и версии, предназначенные подразделению текущего пользователя, либо
	 * иного подразделения, указанного в параметре department. Если есть две версии (сборки) с одинаковым номером
	 * (например, 2.5.1), одна из которых общая, а другая предназначена указанному подразделению --
	 * возвращена будет вторая. Если имеется сборка, предназначенная указанному подразделению,
	 * и более свежая общая -- первая будет возвращена только в том случае, если для неё задано поле
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
			@WebParam(name = "ignoreDepartment ") boolean ignoreDepartment 
	) throws CarabiException {
		List<ProductVersion> versions = getVersionsList(token, productName, department, ignoreDepartment, false);
		ProductVersion lastCommon = null, lastForDepartment = null;
		boolean commonIsNewer = false;
		for (ProductVersion version: versions) {
			if (version.getDestinatedForDepartment() == null) {
				lastCommon = version;
				commonIsNewer = true;
			} else {
				lastForDepartment = version;
				commonIsNewer = false;
			}
		}
		if (commonIsNewer) {
			if (lastForDepartment != null && lastForDepartment.isDoNotAdviceNewerCommon()) {
				return lastForDepartment;
			} else {
				return lastCommon;
			}
		} else {
			return lastForDepartment;
		}
	}
	
	private static int[] parseVersion(String version) {
		String[] elements = version.split("\\.");
		int[] numbers = new int[elements.length];
		int i = 0;
		for (String element: elements) {
			try {
				numbers[i] = Integer.valueOf(element);
				i++;
			} catch (NumberFormatException e) {
				logger.log(Level.WARNING, "parse error", e);
				numbers[i] = 0;
			}
		}
		return numbers;
	}
	
	private static class VersionComparator implements Comparator<ProductVersion> {
		@Override
		public int compare(ProductVersion t, ProductVersion t1) {
			String version = t.getVersionNumber();
			String version1 = t1.getVersionNumber();
			int[] numbers = parseVersion(version);
			int[] numbers1 = parseVersion(version1);
			for (int i=0; i<Math.min(numbers.length, numbers1.length); i++) {
				if (numbers[i] != numbers1[i]) {
					return numbers[i] - numbers1[i];
				}
			}
			if (numbers.length != numbers1.length) { 
				return numbers.length - numbers1.length;
			} else {
				return 0;
			}
		}
	}
}
