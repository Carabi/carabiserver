package ru.carabi.server.soap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
		//Упорядоченный (от старых к новым)список версий.
		List<ProductVersion> versions = getVersionsList(token, productName, department, ignoreDepartment, false);
		String sql = "select * from appl_department.get_departments_branch(?, ?)";
		Query getDepartmentsBranch = em.createNativeQuery(sql);
		getDepartmentsBranch.setParameter(1, token);
		getDepartmentsBranch.setParameter(2, department);
		List departmentsBranchResultList = getDepartmentsBranch.getResultList();
		//Список ID подразделений от требуемого к вышестоящим
		List<Integer> departmentsBranch = new ArrayList<>();
		for (Object departmentID: departmentsBranchResultList) {
			departmentsBranch.add((Integer)departmentID);
		}
		departmentsBranch.add(null);//общие версии, апстрим
		//Индексация версий по подразделениям
		Map<Integer, List<ProductVersion>> versionsByDepartments = new HashMap<>();
		for (ProductVersion version: versions) {
			Integer departmentID = null;
			Department destinatedForDepartment = version.getDestinatedForDepartment();
			if (destinatedForDepartment != null) {
				departmentID = destinatedForDepartment.getId();
			}
			List<ProductVersion> versionsForDepartment = versionsByDepartments.get(departmentID);
			if (versionsForDepartment == null) {
				versionsForDepartment = new ArrayList<>();
			}
			//В каждом versionsForDepartment версии остаются отсортированными по номеру
			versionsForDepartment.add(version);
			versionsByDepartments.put(departmentID, versionsForDepartment);
		}
		ProductVersion result = null;
		//перебираем подразделения от текущего к вышестоящему
		for (Integer departmentID: departmentsBranch) {
			List<ProductVersion> versionsForDepartment = versionsByDepartments.get(departmentID);
			if (versionsForDepartment == null || versionsForDepartment.isEmpty()) {
				continue;
			}
			ProductVersion newestForDepartment = versionsForDepartment.get(versionsForDepartment.size()-1);
			if (result == null) {
				result = newestForDepartment;
			} else {
				//Для очередного (более абстрактного) подразделения берём только более новую версию
				//и только если для ранее выбранной не указано doNotAdviceNewerCommon
				if (result.isDoNotAdviceNewerCommon()) {
					return result;
				}
				if (VersionComparator.compareVersions(newestForDepartment, result) > 0) {
					result = newestForDepartment;
				}
			}
		}
		return result;
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
			return compareVersions(t, t1);
		}
		
		public static int compareVersions(ProductVersion t, ProductVersion t1) {
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
