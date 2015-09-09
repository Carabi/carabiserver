package ru.carabi.server.kernel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Получение и запись сведений о продукции Караби.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Stateless
public class ProductionBean {
	private static final Logger logger = CarabiLogging.getLogger(ProductionBean.class);
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	public SoftwareProduct getProductInfo(String productSysname) {
		TypedQuery<SoftwareProduct> findSoftwareProduct = em.createNamedQuery("findSoftwareProduct", SoftwareProduct.class);
		findSoftwareProduct.setParameter("productName", productSysname);
		List<SoftwareProduct> resultList = findSoftwareProduct.getResultList();
		if (resultList.isEmpty()) {
			return null;
		} else {
			return resultList.get(0);
		}
	}
	
	public List<ProductVersion> getVersionsList(
			UserLogon logon,
			String productName,
			String department,
			boolean ignoreDepartment,
			boolean showAllDepartments)
	throws CarabiException {
		String sql = "select current_versions.*, department.sysname as department_sysname, department.name as department_name\n"+
				//product_version_id, version_number, issue_date, singularity, download_url, is_significant_update, destinated_for_department, do_not_advice_newer_common
				"from appl_production.search_product_versions(?, ?, ?, ?, ?) as current_versions\n" +
				"left join carabi_kernel.department on department.department_id = current_versions.destinated_for_department";
		Query searchProductVersions = em.createNativeQuery(sql);
		searchProductVersions.setParameter(1, logon.getToken());
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
	
	public ProductVersion getLastVersion(
			UserLogon logon,
			String productName,
			String department,
			boolean ignoreDepartment 
	) throws CarabiException {
		//Упорядоченный (от старых к новым)список версий.
		List<ProductVersion> versions = getVersionsList(logon, productName, department, ignoreDepartment, false);
		//Список ID подразделений от требуемого к вышестоящим
		List<Integer> departmentsBranch = getDepartmentsBranch(logon, department);
		return selectRelevantVersion(versions, departmentsBranch);
	}
	
	/**
	 * Выбор подходящей версии.
	 * Выбирается самая свежая версия, из свежих -- предназначенная для нижележащего подразделения.
	 * @param versions список версий продукта -- должен быть упорядоченным от старых к новым
	 * @param departmentsBranch список подразделений пользователя -- должен быть упорядоченным от текущего к вышестоящему
	 * @return выбранная версия. null, если нет ни одной подходящей (пустой список versions,
	 * не пересекаются множества versions.getDestinatedForDepartment и departmentsBranch)
	 */
	private ProductVersion selectRelevantVersion(List<ProductVersion> versions, List<Integer> departmentsBranch) {
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
			//Последняя версия для текущего подразделения
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

	private List<Integer> getDepartmentsBranch(UserLogon logon, String department) {
		String sql = "select * from appl_department.get_departments_branch(?, ?)";
		Query getDepartmentsBranch = em.createNativeQuery(sql);
		getDepartmentsBranch.setParameter(1, logon.getToken());
		getDepartmentsBranch.setParameter(2, department);
		List departmentsBranchResultList = getDepartmentsBranch.getResultList();
		List<Integer> departmentsBranch = new ArrayList<>();
		for (Object departmentID: departmentsBranchResultList) {
			departmentsBranch.add((Integer)departmentID);
		}
		departmentsBranch.add(null);//общие версии, апстрим
		return departmentsBranch;
	}
	
	/**
	 * Возвращает данные о версии софта по ID
	 * @param versionID версия
	 * @return экземпляр ProductVersion, если найден, иначе null
	 */
	public ProductVersion getProductVersion(long versionID) {
		return em.find(ProductVersion.class, versionID);
	}
	
	/**
	 * Поиск версии указанной версии указанного продука для текущего пользователя.
	 * Выполняется поиск по системному наименованию продукта и номеру версии.
	 * Если есть несколько версий для разных подразделений -- возвражается версия
	 * для ближайшего к текущему пользователю подразделения.
	 * @param logon сессия пользователя
	 * @param productName название продукта
	 * @param versionNumber номер версии
	 * @return экземпляр ProductVersion, если найден, иначе null
	 */
	public ProductVersion getProductVersion(UserLogon logon, String productName, String versionNumber) {
		TypedQuery<ProductVersion> getProductNameNumberVersion = em.createNamedQuery("getProductNameNumberVersion", ProductVersion.class);
		getProductNameNumberVersion.setParameter("productName", productName);
		getProductNameNumberVersion.setParameter("versionNumber", versionNumber);
		List<ProductVersion> resultList = getProductNameNumberVersion.getResultList();
		if (resultList.isEmpty()) {
			return null;
		}
		List departmentsBranch = getDepartmentsBranch(logon, null);
		return selectRelevantVersion(resultList, departmentsBranch);
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
}
