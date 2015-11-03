package ru.carabi.server.kernel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.Permission;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.entities.Publication;
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
	
	@EJB private UsersPercistenceBean usersPercistence;
	@EJB private DepartmentsPercistenceBean departmentsPercistence;
	@EJB private AdminBean admin;
	/**
	 * Получение списка продуктов/модулей, с которыми может работать пользователь.
	 * @param logon сессия текущего пользователя
	 * @param controlResources проверять, доступны ли продукты на текущем сервере и БД
	 * @return
	 */
	public List<SoftwareProduct> getAvailableProduction(UserLogon logon, boolean controlResources) {
		return getAvailableProduction(logon, null, controlResources);
	}
	
	/**
	 * Получение списка продуктов/модулей, с которыми может работать пользователь.
	 * @param logon сессия текущего пользователя
	 * @param currentProduct родительский продукт для модулей (null, если искать всё)
	 * @param controlResources проверять, доступны ли продукты на текущем сервере и БД
	 * @return
	 */
	public List<SoftwareProduct> getAvailableProduction(UserLogon logon, String currentProduct, boolean controlResources) {
		String sql;
		if (StringUtils.isEmpty(currentProduct)) {
			sql = "select production_id, name, sysname, home_url, parent_production from appl_production.get_available_production(?, ?)";
		} else {
			sql = "select production_id, name, sysname, home_url, parent_production from appl_production.get_available_production(?, ?, ?)";
		}
		Query query = em.createNativeQuery(sql);
		query.setParameter(1, logon.getToken());
		int currentProductIsSet = 0;
		if (!StringUtils.isEmpty(currentProduct)) {
			query.setParameter(2, currentProduct);
			currentProductIsSet = 1;
		}
		query.setParameter(currentProductIsSet + 2, controlResources);
		List resultList = query.getResultList();
		List<SoftwareProduct> result = new ArrayList<>(resultList.size());
		for (Object row: resultList) {
			Object[] data = (Object[])row;
			SoftwareProduct product = new SoftwareProduct();
			product.setId((Integer) data[0]);
			product.setName((String) data[1]);
			product.setSysname((String) data[2]);
			product.setHomeUrl((String) data[3]);
			product.setParentProductId((Integer) data[4]);
			result.add(product);
		}
		return result;
	}
	
	/**
	 * Поиск программного продукта {@link SoftwareProduct} по кодовому наименованию.
	 * @param productSysname кодовое наименование
	 * @return Найденный объект SoftwareProduct, null, если не найден.
	 */
	public SoftwareProduct findProduct(String productSysname) {
		TypedQuery<SoftwareProduct> findSoftwareProduct = em.createNamedQuery("findSoftwareProduct", SoftwareProduct.class);
		findSoftwareProduct.setParameter("productName", productSysname);
		List<SoftwareProduct> resultList = findSoftwareProduct.getResultList();
		if (resultList.isEmpty()) {
			return null;
		} else {
			return resultList.get(0);
		}
	}
	
	/**
	 * Получение списка доступных версий одного продукта.
	 * @param logon сессия текущего пользователя
	 * @param productName кодовое название продукта
	 * @param department подразделение, к которому должны относиться версии (если null и ignoreDepartment == false, то берётся из текущего пользователя)
	 * @param ignoreDepartment не показывать версии для подразделений (только общие)
	 * @param showAllDepartments показывать версии для всех подразделений (параметры department и ignoreDepartment игнорируются)
	 * @return
	 * @throws CarabiException 
	 */
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
			Long fileId = (Long) data[5];
			if (fileId != null) {
				FileOnServer file = new FileOnServer();
				file.setId(fileId);
				productVersion.setFile(file);
			}
			productVersion.setIsSignificantUpdate((Boolean) data[6]);
			productVersion.setDoNotAdviceNewerCommon((Boolean) data[8]);
			final Integer departmentId = (Integer) data[7];
			if (departmentId != null) {
				Department departmentObj = new Department();
				departmentObj.setId(departmentId);
				departmentObj.setSysname((String) data[9]);
				departmentObj.setName((String) data[10]);
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
	
	/**
	 * Изменить доступ пользователя к продукту.
	 * @param logon сессия текущего пользователя
	 * @param productSysname кодовое название продукта / модуля
	 * @param login логин редактируемого пользователя
	 * @param isAllowed true -- дать доступ, false -- снять доступ
	 * @throws ru.carabi.server.CarabiException
	 */
	public void allowForUser(UserLogon logon, String productSysname, String login, boolean isAllowed) throws CarabiException {
		CarabiUser user = usersPercistence.findUser(login);
		SoftwareProduct product = this.findProduct(productSysname);
		allowForUser(logon, product, user, isAllowed);
	}
	
	/**
	 * Изменить доступ пользователя к продукту.
	 * @param logon сессия текущего пользователя
	 * @param product
	 * @param isAllowed true -- дать доступ, false -- снять доступ
	 * @param user
	 * @throws ru.carabi.server.CarabiException
	 */
	public void allowForUser(UserLogon logon, SoftwareProduct product, CarabiUser user, boolean isAllowed) throws CarabiException {
		Permission permissionToUse = product.getPermissionToUse();
		if (permissionToUse == null) {
			return;
		}
		Integer parentProductId = product.getParentProductId();
		if (parentProductId != null && isAllowed) {
			allowForUser(logon, em.find(SoftwareProduct.class, parentProductId), user, isAllowed);
		}
		admin.assignPermissionForUser(logon, user, permissionToUse, isAllowed);
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
	 * @param versionID id версии
	 * @return экземпляр ProductVersion, если найден, иначе null
	 */
	public ProductVersion getProductVersion(long versionID) {
		return em.find(ProductVersion.class, versionID);
	}
	
	/**
	 * Возвращает данные о публикации по ID
	 * @param publicationID id публикации
	 * @return экземпляр Publication, если найден, иначе null
	 */
	public Publication getPublication(long publicationID) {
		return em.find(Publication.class, publicationID);
	}
	
	/**
	 * Поиск версии указанной версии указанного продука для текущего пользователя.
	 * Выполняется поиск по системному наименованию продукта и номеру версии.
	 * Если есть несколько версий для разных подразделений -- возвражается версия
	 * для ближайшего к текущему пользователю подразделения.
	 * @param logon сессия текущего пользователя
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
	
	public List<Publication> getAvailablePublication(UserLogon logon) {
		CarabiUser user = logon.getUser();
		TypedQuery<Publication> getUserPublications = em.createNamedQuery("getUserPublications", Publication.class);
		getUserPublications.setParameter("user", user);
		getUserPublications.setParameter("department", user.getDepartment());
		getUserPublications.setParameter("corporation", user.getCorporation());
		List<Publication> resultList = getUserPublications.getResultList();
		//после получения потенциально доступных публикаций проверим, какие
		//адресованы лично пользователю, а на что он должен иметь права
		final ArrayList<Publication> result = new ArrayList<>();
		Collection<Permission> userPermissions = usersPercistence.getUserPermissions(logon);
		for (Publication publication: resultList) {
			if (allowedForUser(user, userPermissions, publication)) {
				result.add(publication);
			}
		}
		return result;
	}
	
	/**
	 * Проверка, что пользователь может читать публикацию.
	 * Да, если:
	 * <ul>
	 * <li>публикация адресована лично пользователю
	 * <li>публикация адресована подразделению пользователя и он имеет право, если указано
	 * <li>публикация не имеет конкретных адресатов. Пользователь должен иметь право, если указано
	 * </ul>
	 * @param user пользователь
	 * @param userPermissions права пользователя (при вызове в цикле рекомендуется считать заранее)
	 * @param publication публикация
	 * @return Может ли пользователь читать публикацию
	 */
	public boolean allowedForUser(CarabiUser user, Collection<Permission> userPermissions, Publication publication) {
		if (user.equals(publication.getDestinatedForUser())) {
			return true;
		}
		if (userPermissions == null) {
			userPermissions = usersPercistence.getUserPermissions(user);
		}
		if (publication.getPermissionToRead() == null || userPermissions.contains(publication.getPermissionToRead())) {
			return true;
		}
		return false;
	}
	
	/**
	 * Проверка, имеет ли право ли пользователь использовать продукт.
	 * Проверка доступности на сервере и БД не проверяется.
	 * @param logon сессия текущего пользователя
	 * @param productSysname кодовое название продукта
	 * @return 
	 */
	public boolean productionIsAllowed(UserLogon logon, String productSysname) {
		
		Query productionIsAvailable = em.createNativeQuery("select * from appl_production.production_is_available(? ,?, false)");
		productionIsAvailable.setParameter(1, logon.getToken());
		productionIsAvailable.setParameter(2, productSysname);
		List resultList = productionIsAvailable.getResultList();
		return (Boolean)resultList.get(0);
	}
	
	/**
	 * Создание публикации с записью данных в файл и БД.
	 * @param logon сессия текущего пользователя
	 * @param name название публикации
	 * @param description описание публикации
	 * @param inputStream данные для записи в файл
	 * @param filename пользовательское название файла
	 * @param receiver пользователь-получатель
	 * @param departmentDestination подразделение-получатель
	 * @param isCommon общая публикация (receiver и departmentDestination null)
	 * @return
	 * @throws CarabiException
	 * @throws IOException 
	 */
	public Publication uploadPublication(UserLogon logon, String name, String description, InputStream inputStream, String filename, CarabiUser receiver, Department departmentDestination, boolean isCommon) throws CarabiException, IOException {
		if (departmentDestination == null && receiver == null && !isCommon) {
			throw new CarabiException("Illegal arguments: no recevier: user, department or everybody");
		}
		boolean foreignReceiver = false;
		if (receiver != null) {
			if (receiver.getCorporation() == null || !receiver.getCorporation().equals(logon.getUser().getCorporation())) {
				foreignReceiver = true;
			}
		}
		
		boolean foreignDepartment = false;
		List<Department> departmentBranch = null;
		if (departmentDestination != null) {
			departmentBranch = departmentsPercistence.getDepartmentBranch(logon);
			foreignDepartment = !departmentBranch.contains(departmentDestination);
		}
		//Создавать общие, адресованные чужому подразделению или человеку из другого
		//подразделения публикации может только администратор
		if (isCommon || foreignReceiver || foreignDepartment) {
			logon.assertAllowed("ADMINISTRATING-PUBLICATIONS-EDIT");
		} else {
			logon.assertAllowedAny(new String[]{"ADMINISTRATING-PUBLICATIONS-EDIT", "MANAGING-PUBLICATIONS-EDIT"});
		}
		StringBuilder pathBuilder = new StringBuilder (Settings.PUBLICATIONS_LOCATION);
		if (departmentBranch != null) {
			for (Department department: departmentBranch) {
				pathBuilder.append(File.separatorChar).append(department.getSysname());
			}
		}
		Files.createDirectories(new File(pathBuilder.toString()).toPath());
		pathBuilder.append(File.separatorChar);
		pathBuilder.append(filename);
		String path = pathBuilder.toString();
		FileOnServer attachment = Utls.saveToFileOnServer(inputStream, path, filename);
		Publication publication = new Publication();
		publication.setName(name);
		publication.setAttachment(attachment);
		publication.setDescription(description);
		publication.setDestinatedForDepartment(departmentDestination);
		publication.setDestinatedForUser(receiver);
		publication.setIssueDate(new Date());
		return em.merge(publication);
	}
	
	/**
	 * Создание версии продукта с записью данных в файл и БД.
	 * @param logon
	 * @param product продукт, для которого создаётся версия
	 * @param versionNumber номер версии в формате "1.2.3.4"
	 * @param inputStream данные для записи в файл
	 * @param filename
	 * @param singularity особенности данной версии
	 * @param significantUpdate является важным обновлением
	 * @param departmentDestination компания, которой адресована данная сборка
	 * @return 
	 * @throws ru.carabi.server.CarabiException
	 * @throws java.io.IOException
	 */
	public ProductVersion uploadProductVersion(UserLogon logon, SoftwareProduct product, String versionNumber, InputStream inputStream, String filename, String singularity, boolean significantUpdate, Department departmentDestination) throws CarabiException, IOException {
		logon.assertAllowed("ADMINISTRATING-PRODUCTS-EDIT");
		StringBuilder pathBuilder = new StringBuilder (Settings.SOFTWARE_LOCATION);
		pathBuilder.append(File.separatorChar).append(product.getSysname());
		if (departmentDestination != null) {
			List<Department> departmentBranch = departmentsPercistence.getDepartmentBranch(departmentDestination);
			for (Department department: departmentBranch) {
				pathBuilder.append(File.separatorChar).append(department.getSysname());
			}
		}
		Files.createDirectories(new File(pathBuilder.toString()).toPath());
		pathBuilder.append(File.separatorChar).append(product.getSysname());
		pathBuilder.append("-").append(versionNumber);
		FileOnServer fileOnServer = Utls.saveToFileOnServer(inputStream, pathBuilder.toString(), filename);
		ProductVersion version = new ProductVersion();
		version.setCarabiProduct(product);
		version.setVersionNumber(versionNumber);
		version.setSingularity(singularity);
		version.setFile(fileOnServer);
		version.setDestinatedForDepartment(departmentDestination);
		version.setIssueDate(new Date());
		version.setIsSignificantUpdate(significantUpdate);
		return em.merge(version);
	}
	
	/**
	 * Удаление версий одного программного продукта, общих или предназначенных одному подразделению
	 * @param product
	 * @param departmentDestination 
	 */
	public void removeVersions(SoftwareProduct product, Department departmentDestination) {
		TypedQuery<ProductVersion> getProductVersionsForDepartment;
		if (departmentDestination == null) {
			getProductVersionsForDepartment = em.createNamedQuery("getProductVersionsForNobody", ProductVersion.class);
		} else {
			getProductVersionsForDepartment = em.createNamedQuery("getProductVersionsForDepartment", ProductVersion.class);
			getProductVersionsForDepartment.setParameter("departmentDestination", departmentDestination);
		}
		getProductVersionsForDepartment.setParameter("product", product);
		List<ProductVersion> resultList = getProductVersionsForDepartment.getResultList();
		for (ProductVersion removingVersion: resultList) {
			FileOnServer file = removingVersion.getFile();
			if (file != null) {
				new File(file.getContentAddress()).delete();
				em.remove(file);
			}
			em.remove(removingVersion);
		}
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
