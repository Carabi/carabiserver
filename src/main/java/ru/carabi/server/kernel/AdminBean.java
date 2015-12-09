package ru.carabi.server.kernel;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrBuilder;
import ru.carabi.libs.CarabiEventType;
import ru.carabi.libs.CarabiFunc;
import ru.carabi.server.CarabiException;
import ru.carabi.server.EntityManagerTool;
import ru.carabi.server.PermissionException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.Permission;
import ru.carabi.server.entities.Phone;
import ru.carabi.server.entities.PhoneType;
import ru.carabi.server.entities.QueryCategory;
import ru.carabi.server.entities.QueryEntity;
import ru.carabi.server.entities.QueryParameterEntity;
import ru.carabi.server.entities.UserRelation;
import ru.carabi.server.entities.UserRelationType;
import ru.carabi.server.entities.UserRole;
import ru.carabi.server.entities.UserStatus;

@Stateless
/**
 * Методы для управления сервером и основными данными в служебной БД.
 *
 * @author sasha<kopilov.ad@gmail.com>
 * @author misha<mikhail.bortsov@gmail.com>
 * 
 * @todo: Refactoring, class hierarchy, close() method shouldn't be called at all! use try/closeable 
 * @todo: mark logging as complete debugging task (in activity ) of save (+) problem
 */
public class AdminBean {
	private static final Logger logger = Logger.getLogger(AdminBean.class.getName());
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	private @EJB ConnectionsGateBean cg;
	private @EJB EventerBean eventer;
	private @EJB ImagesBean images;
	private @EJB UsersControllerBean uc;
	private @EJB DepartmentsPercistenceBean departmentsPercistence;
	
	/**
	 * Получение списка схем, доступных пользователю.
	 * @param logon текущий пользователь
	 * @param login логин
	 * @return список псевдонимов схем
	 * @throws CarabiException если такого пользователя нет или текущий пользователь не имеет право смотреть других
	 */
	public List<String> getUserAllowedSchemas(UserLogon logon, String login) throws CarabiException {
		if (!logon.getUser().getLogin().equals(login)) {
			logon.assertAllowed("ADMINISTRATING-USERS-VIEW");
		}
		
		logger.log(Level.FINEST,
		                "package ru.carabi.server.kernel.AdminBean"
		                    +".getUserAllowedSchemas(String login)"
		                    +" caller params: {0}", 
		                new Object[]{login});  
		close();
		final CarabiUser user = uc.findUser(login);
		final Collection<ConnectionSchema> allowedSchemas = user.getAllowedSchemas();
		List<String> schemasList = new ArrayList<>(allowedSchemas.size());
		for (ConnectionSchema allowedSchema: allowedSchemas) {
			logger.log(Level.INFO, "{0} allowed {1} ({2}, {3})", new Object[]{login, allowedSchema.getName(), allowedSchema.getSysname(), allowedSchema.getJNDI()});
			schemasList.add(allowedSchema.getSysname());
		}
		close();
		return schemasList;
	}
	
	/**
	 * Возвращает список пользователей с указанным статусом из ядровой базы.
	 * Если статус не указан, он игнорируется/
	 * Берутся пользователи, имеющие доступ к базе, с которой работает текущий пользователь.
	 * Результат возвращается в JSON в формате 
	 * <code>
		 { "carabiusers": [ 
		                    { "carabiuser", [ { "id": "" }, { "name", ""} ]}, 
		                      ...
		 ]}
	 * </code>
	 * @param logon сессия текущего пользователя
	 * @param statusSysname искомый статус
	 * @return строка c Json-объектом
	 * @throws ru.carabi.server.CarabiException если текущий пользователь не имеет право смотреть других
	 */
	public String getUsersList(UserLogon logon, String statusSysname) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-USERS-VIEW");
		Query query;
		if (statusSysname != null) {
			query = em.createNamedQuery("getSchemaUsersWithStatusList");
			query.setParameter("status", statusSysname);
		} else {
			query = em.createNamedQuery("getSchemaUsersList");
		}
		query.setParameter("schema_id", logon.getSchema().getId());
		final List resultList = query.getResultList();
		close();
		
		final JsonArrayBuilder jsonUsers = Json.createArrayBuilder();
		final Iterator usersIterator = resultList.iterator();
		while (usersIterator.hasNext()) {
			final Object[] dbUser = (Object[]) usersIterator.next();
			final JsonObjectBuilder jsonUserDetails = Json.createObjectBuilder();
			Utls.addJsonObject(jsonUserDetails, "id", dbUser[0]);
			Utls.addJsonObject(jsonUserDetails, "login", dbUser[1]);
			final StrBuilder name = new StrBuilder();
			name.setNullText("");
			name.append(dbUser[2]).append(" ").append(dbUser[3]).append(" ")
					.append(dbUser[4]);
			Utls.addJsonObject(jsonUserDetails, "name", name.toString());

			final JsonObjectBuilder jsonUser = Json.createObjectBuilder();
			jsonUser.add("carabiuser", jsonUserDetails);
			jsonUsers.add(jsonUser);
		}

		// handles empty list case - just add root object "carabiusers"
		final JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("carabiusers", jsonUsers);
		
		// returns results
		return result.build().toString();
	}
	
	public String getUser(UserLogon logon, Long id) throws CarabiException {
		if (!logon.getUser().getId().equals(id)) {
			logon.assertAllowed("ADMINISTRATING-USERS-VIEW");
		}
		final CarabiUser carabiUser = em.find(CarabiUser.class, id);
		if (null == carabiUser) {
			final CarabiException e = new CarabiException(
					"Пользователь не найден по ID: " + id.toString());
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		// fill in user fields
		final JsonObjectBuilder jsonUserDetails = Json.createObjectBuilder();
		jsonUserDetails.add("id", carabiUser.getId());
		Utls.addJsonObject(jsonUserDetails, "firstName", carabiUser.getFirstname());
		Utls.addJsonObject(jsonUserDetails, "middleName", carabiUser.getMiddlename());
		Utls.addJsonObject(jsonUserDetails, "lastName", carabiUser.getLastname());
		Utls.addJsonObject(jsonUserDetails, "department", carabiUser.getCarabiDepartment());
		Utls.addJsonObject(jsonUserDetails, "role", carabiUser.getCarabiRole());
		Utls.addJsonObject(jsonUserDetails, "login", carabiUser.getLogin());
		Utls.addJsonObject(jsonUserDetails, "password", carabiUser.getPassword());
		Utls.addJsonObject(jsonUserDetails, "defaultSchemaId", (null == carabiUser.getDefaultSchema()) ? "" : carabiUser.getDefaultSchema().getId());
		
		// fill in schemas list with regard to whether a schema is allowed or not
		// 1. read from kernel db
		final TypedQuery<ConnectionSchema> query = em.createNamedQuery("fullSelectAllSchemas", ConnectionSchema.class);
		final List<ConnectionSchema> connectionSchemas = query.getResultList();
		close();
		// 2. make json
		final JsonArrayBuilder jsonConnectionSchemas = Json.createArrayBuilder();
		for (ConnectionSchema connectionSchema: connectionSchemas) {
			// see if the current is allowed for user
			boolean isAllowed = false;
			
			close();
			final Collection allowedSchemas = carabiUser.getAllowedSchemas();
			final Iterator<ConnectionSchema> allowedSchemasIterator = 
					allowedSchemas.iterator();
			while (allowedSchemasIterator.hasNext()) {
				final ConnectionSchema allowedSchema = allowedSchemasIterator.next();
				if (allowedSchema.getId().equals(connectionSchema.getId())) {
					isAllowed = true;
					break;
				}
			}
			
			final JsonObjectBuilder jsonConnectionSchemaDetails = Json.createObjectBuilder();
			jsonConnectionSchemaDetails.add("id", connectionSchema.getId());
			Utls.addJsonObject(jsonConnectionSchemaDetails, "name", connectionSchema.getName());
			Utls.addJsonObject(jsonConnectionSchemaDetails, "sysName", connectionSchema.getSysname());
			Utls.addJsonObject(jsonConnectionSchemaDetails, "jndi", connectionSchema.getJNDI());
			jsonConnectionSchemaDetails.add("isAllowed", isAllowed);
			
			final JsonObjectBuilder jsonConnectionSchema = Json.createObjectBuilder();
			jsonConnectionSchema.add("connectionSchema", jsonConnectionSchemaDetails);
			
			jsonConnectionSchemas.add(jsonConnectionSchema);
		}
		jsonUserDetails.add("connectionSchemas", jsonConnectionSchemas);

		// add the list of user phones to jsonUserDetails
		final TypedQuery<Phone> phonesQuery = em.createNamedQuery("selectUserPhones", Phone.class);
		phonesQuery.setParameter("ownerId", id);
		final List<Phone> phones = phonesQuery.getResultList();
		close();
		final JsonArrayBuilder jsonPhones = Json.createArrayBuilder();
		for (Phone phone: phones) {
			final JsonObjectBuilder jsonPhoneDetails = Json.createObjectBuilder();

			// add all fileds, but ownerId, which is the parameter of this method (so caller already has it)
			jsonPhoneDetails.add("id", phone.getId());
			Utls.addJsonObject(jsonPhoneDetails, "phoneType",
					null == phone.getPhoneType() ? null : phone.getPhoneType().getId());
			Utls.addJsonObject(jsonPhoneDetails, "countryCode", phone.getCountryCode());
			Utls.addJsonObject(jsonPhoneDetails, "regionCode", phone.getRegionCode());
			Utls.addJsonObject(jsonPhoneDetails, "mainNumber", phone.getMainNumber());
			Utls.addJsonObject(jsonPhoneDetails, "suffix", phone.getSuffix());
			Utls.addJsonObject(jsonPhoneDetails, "schemaId",
					null == phone.getSipSchema() ? null : phone.getSipSchema().getId());
			Utls.addJsonObject(jsonPhoneDetails, "orderNumber", phone.getOrdernumber());

			// pack all phone details (write to jsonPhone and add it to jsonPhones)
			final JsonObjectBuilder jsonPhone = Json.createObjectBuilder();
			jsonPhone.add("phone", jsonPhoneDetails);
			jsonPhones.add(jsonPhone);
		}
		jsonUserDetails.add("phones", jsonPhones);
		
		// build a response string out of json and return it as the result
		final JsonObjectBuilder jsonUser = Json.createObjectBuilder();
		jsonUser.add("carabiuser", jsonUserDetails);
		return jsonUser.build().toString();
	}
	
	/**
	 * Редактирование или создание пользователя Carabi из Web-сервиса.
	 * @param logon сессия текущего пользователя
	 * @param userDetails Информация о пользователе
	 * @param updateSchemas обновлять данные о доступе к схемам (если редактируем имевшегося пользователя из Oracle -- то нет)
	 * @return ID пользователя
	 * @throws CarabiException
	 */
	public Long saveUser(UserLogon logon, JsonObject userDetails, boolean updateSchemas) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
		// create or fetch user
		String idStr = Utls.getNativeJsonString(userDetails,"id");
		CarabiUser user = EntityManagerTool.createOrFind(em, CarabiUser.class, Long.class, idStr);
		// set simple user fields
		user.setFirstname(userDetails.getString("firstName"));
		user.setMiddlename(userDetails.getString("middleName"));
		user.setLastname(userDetails.getString("lastName"));
		user.setLogin(userDetails.getString("login"));
		user.setPassword(userDetails.getString("password"));
		
		//deprecated fields
		user.setCarabiDepartment(userDetails.getString("department"));
		user.setCarabiRole(userDetails.getString("role"));
		
		//update corporation/department if inputed
		if (userDetails.containsKey("corporationSysname")) {
			Department corporation = departmentsPercistence.findDepartment(userDetails.getString("corporationSysname"));
			user.setCorporation(corporation);
		}
		
		if (userDetails.containsKey("departmentSysname")) {
			Department department = departmentsPercistence.findDepartment(userDetails.getString("departmentSysname"));
			user.setDepartment(department);
		}
		
		// update phones (incorrect from some schemas)
		try {
			if (user.getPhonesList() != null) {
				for (Phone phone: user.getPhonesList()) {//удаляем старые телефоны, если на входе есть новые
					em.remove(phone);
				}
				close(); // fix for error: non persisted object in a relationship marked for cascade persist.
						 // releasing a user object before updating its list of phones.
			}
			String[] phones = {};
			if(!("".equals(userDetails.getString("phones")))) { // if phones string is empty, just use empty phones list
				phones = userDetails.getString("phones").replace("||||", "| | ||").replace("^||", "^| |").replace("|||", "| ||").split("\\|\\|");
					// using replace to handle the cases when "" is set for phone schema, phone type or both of them. interpret " " as NULL below.
					// there is an important assumption made that we have trailing "^" at the end of all numbers (so the client-side must set it even
					// if suffix is empty)
			}
			ArrayList<Phone> phonesList = new ArrayList<>(phones.length);
			int phoneOrderNumber = 1;
			for (String phoneStr: phones) {
				String[] phoneElements = phoneStr.split("\\|");
				if (phoneElements.length == 0 || StringUtils.isEmpty(phoneElements[0])) {
					continue;
				}
				Phone phone = new Phone();
				phone.parse(phoneElements[0]);
				PhoneType phoneType = null;
				if (phoneElements.length > 1 && !" ".equals(phoneElements[1])) {
					final String phoneTypeName = phoneElements[1];
					final TypedQuery<PhoneType> findPhoneType = em.createNamedQuery("findPhoneType", PhoneType.class);
					findPhoneType.setParameter("name", phoneTypeName);
					final List<PhoneType> resultList = findPhoneType.getResultList();
					if (resultList.isEmpty()) {
						phoneType = new PhoneType();
						phoneType.setName(phoneTypeName);
						phoneType.setSysname(phoneTypeName);
					} else {
						phoneType = resultList.get(0);
					}
					if (phoneType.getSysname().equals("SIP")) {
						phone.setSipSchema(user.getDefaultSchema());
					}
				}
				if (phoneElements.length > 2) {
					if (!("".equals(phoneElements[2]) || " ".equals(phoneElements[2]))) {
						final ConnectionSchema phoneSchema = em.find(ConnectionSchema.class, Integer.parseInt(phoneElements[2]));
						phone.setSipSchema(phoneSchema);
					} else {
						phone.setSipSchema(null);
					}
				}
				phone.setPhoneType(phoneType);
				phone.setOrdernumber(phoneOrderNumber);
				phoneOrderNumber++;
				phone.setOwner(user);
				phonesList.add(phone);
			}
			user.setPhonesList(phonesList);
		} catch (Exception ex) { logger.log(Level.WARNING, "could not parce phones", ex);}
		
		// updates schemas
		if (updateSchemas) {
			// схема по умолчанию
			if (!userDetails.containsKey("defaultSchemaId")) {
				user.setDefaultSchema(null);
			} else {
				int schemaId;
				try {
					 schemaId = userDetails.getInt("defaultSchemaId");
				} catch (NumberFormatException nfe) {
					final CarabiException e = new CarabiException(
							"Неверный формат ID схемы подключения. "
							+ "Ожидется: java.lang.Integer", nfe);
					logger.log(Level.WARNING, "" , e);
					throw e;
				}
				final ConnectionSchema schema = em.find(ConnectionSchema.class, schemaId);
				user.setDefaultSchema(schema);
			}

			// обновеление списка доступных схем 
			final JsonArray allowedSchemaIds = userDetails.getJsonArray("allowedSchemaIds");

			if (allowedSchemaIds.size()>0) {
				final Collection<Integer> listAllowedSchemaIds = new ArrayList(allowedSchemaIds.size());
				for (int i = 0; i < allowedSchemaIds.size(); i++) {
					listAllowedSchemaIds.add(allowedSchemaIds.getInt(i));
				}

				final TypedQuery<ConnectionSchema> allowedSchemasQuery = 
					em.createQuery(
						"select cs from ConnectionSchema cs where cs.id IN :ids", 
						ConnectionSchema.class);
				allowedSchemasQuery.setParameter("ids", listAllowedSchemaIds);
				final List<ConnectionSchema> allowedSchemas = 
						allowedSchemasQuery.getResultList();

				user.setAllowedSchemas(allowedSchemas);
			} else {
				user.setAllowedSchemas(Collections.EMPTY_LIST);
			}
		}
		// save user data
		user = em.merge(user);
		close();
		
		return user.getId();
	}
	
	/**
	 * Удаление пользователя из ядровой БД
	 * @param logon сессия текущего пользователя
	 * @param login логин удаляемого пользователя
	 * @throws CarabiException 
	 */
	public void deleteUser(UserLogon logon, String login) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
		if (login == null) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "пользователя, т.к. не задан login удаляемой записи.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		final Query query = em.createQuery("DELETE FROM CarabiUser cu WHERE cu.login = :login");
		int deletedCount = query.setParameter("login", login).executeUpdate();
		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Нет записи с таким login-ом. Ошибка удаления "
					+ "пользователя при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
	}
	
	public List<UserRole> getRolesList(UserLogon logon) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-ROLES-VIEW");
		TypedQuery<UserRole> getAllUsersRoles = em.createNamedQuery("getAllUsersRoles", UserRole.class);
		return getAllUsersRoles.getResultList();
	}
	
	public Collection<Permission> getRolePermissions(UserLogon logon, JsonObject roleDetails) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-ROLES-VIEW");
		UserRole userRole = EntityManagerTool.findByIdOrSysname(em, UserRole.class, roleDetails);
		return userRole.getPermissions();
	}
	/**
	 * Создать или изменить роль пользователя
	 * @param logon
	 * @param roleDetails
	 * @throws CarabiException 
	 */
	public void saveRole(UserLogon logon, JsonObject roleDetails) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-ROLES-EDIT");
		UserRole userRole;
		if (roleDetails.containsKey("id")) {
			userRole = EntityManagerTool.createOrFind(em, UserRole.class, Integer.class, roleDetails.getString("id"));
		} else {
			userRole = EntityManagerTool.findBySysnameOrCreate(em, UserRole.class, roleDetails.getString("sysname"));
		}
		userRole.setName(roleDetails.getString("name"));
		userRole.setSysname(roleDetails.getString("sysname"));
		userRole.setDescription(roleDetails.getString("description"));
		em.merge(userRole);
	}
	
	/**
	 * Удалить роль пользователя по ID или Sysname
	 * @param logon
	 * @param roleDetails
	 * @throws CarabiException 
	 */
	public void deleteRole(UserLogon logon, JsonObject roleDetails) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-ROLES-EDIT");
		UserRole userRole = EntityManagerTool.findByIdOrSysname(em, UserRole.class, roleDetails);
		em.remove(userRole);
	}
	
	/**
	 * Создание или редактирование подразделения.
	 * Параметр departmentData должен содержать поля:
	 * <ul>
	 * <li>id &mdash; первичный ключ (пустой при создании нового объекта)</li>
	 * <li>name &mdash; название</li>
	 * <li>description &mdash; описание</li>
	 * <li>sysname &mdash; кодовое имя (генерируется и возвращается, если не задано)</li>
	 * <li>parent_id &mdash; первичный ключ родительского подразделения или</li>
	 * <li>parent_sysname &mdash; кодовое имя родительского подразделения</li>
	 * </ul>
	 * Если на вход нет ни sysname, ни id &ndash; создаётся новый объект, sysname генерируется
	 * Иначе ищется соответствующий объект или создаётся новый.
	 * <p>
	 * При генерации sysname если на вход подан parent_sysname (а не parent_id) &ndash;
	 * предполагается, что клиент не может оперировать уникальной связкой "parent_id + sysname",
	 * поэтому parent_sysname дописывается в текущий для обеспечения уникальности.
	 * <p>
	 * Пользователь, имеющий право "ADMINISTRATING-DEPARTMENTS-EDIT", может создать или изменить любое подразделение.
	 * Пользователь, имеющий право "MANAGING-DEPARTMENTS-EDIT" &mdash только с подконтрольным ему parent.
	 * @param logon Сессия текущего пользователя
	 * @param departmentData данные о подразделении
	 * @return 
	 * @throws ru.carabi.server.CarabiException 
	 */
	public Department saveDepartment(UserLogon logon, JsonObject departmentData) throws CarabiException {
		Department parentDepartment = null;
		//Создавать вложенные подразделения может менеджер, корневые -- только администратор
		boolean addParentSysname = false;
		if (departmentData.containsKey("parent_sysname") && !StringUtils.isEmpty(departmentData.getString("parent_sysname"))) {
			addParentSysname = true;
			logon.assertAllowedAny(new String[]{"ADMINISTRATING-DEPARTMENTS-EDIT", "MANAGING-DEPARTMENTS-EDIT"});
			String parentSysname = departmentData.getString("parent_sysname");
			parentDepartment = departmentsPercistence.getDepartment(parentSysname);
			if (!(uc.userHavePermission(logon, "ADMINISTRATING-DEPARTMENTS-EDIT") || departmentsPercistence.isDepartmentAvailable(logon, parentDepartment))) {
				throw new PermissionException(logon, "User " + logon.getUser().getLogin() + " can not use department " + parentSysname + " as parent");
			}
		} else if (departmentData.containsKey("parent_id") && !StringUtils.isEmpty(departmentData.getString("parent_id"))) {
			logon.assertAllowedAny(new String[]{"ADMINISTRATING-DEPARTMENTS-EDIT", "MANAGING-DEPARTMENTS-EDIT"});
			String parentID = departmentData.getString("parent_id");
			parentDepartment = em.find(Department.class, Integer.valueOf(parentID));
			if (!(uc.userHavePermission(logon, "ADMINISTRATING-DEPARTMENTS-EDIT") || departmentsPercistence.isDepartmentAvailable(logon, parentDepartment))) {
				throw new PermissionException(logon, "User " + logon.getUser().getLogin() + " can not use department " + parentID + " as parent");
			}
		} else {
			logon.assertAllowed("ADMINISTRATING-DEPARTMENTS-EDIT");
		}
		//Создание или поиск объекта в зависимости от поданных идентификаторов
		Department department;
		if (!(departmentData.containsKey("id") || departmentData.containsKey("sysname"))) {
			department = new Department();
		} else if (departmentData.containsKey("id")) {
			String idStr = departmentData.getString("id");
			department = EntityManagerTool.createOrFind(em, Department.class, Integer.class, idStr);
			department.setId(Integer.valueOf(idStr));
		} else {
			department = departmentsPercistence.findDepartment(departmentData.getString("sysname"));
			if (department == null) {
				department = new Department();
			}
		}
		
		
		department.setName(departmentData.getString("name"));
		department.setDescription(departmentData.getString("description"));
		if (departmentData.containsKey("sysname")) {
			department.setSysname(departmentData.getString("sysname"));
		} else {
			String sysname = CarabiFunc.cyrillicToAscii(department.getName()).replace(' ', '_');
			if (parentDepartment != null && addParentSysname) {
				sysname = parentDepartment.getSysname() + "-" + sysname;
			}
			department.setSysname(sysname);
		}
		if (parentDepartment != null) {
			department.setParentDepartmentId(parentDepartment.getId());
		}
		department = em.merge(department);
		return department;
	}
	
	public void addUserDepartmentRelation(UserLogon logon, String userLogin, String departmentSysname) throws CarabiException {
		logon.assertAllowedAll(new String[] {"ADMINISTRATING-USERS-EDIT", "ADMINISTRATING-DEPARTMENTS-EDIT"});
		CarabiUser user = uc.findUser(userLogin);
		Department department = departmentsPercistence.getDepartment(departmentSysname);
		Collection<Department> relatedDepartments = user.getRelatedDepartments();
		if (!relatedDepartments.contains(department)) {
			relatedDepartments.add(department);
			em.merge(user);
		}
	}
	
	public void removeUserDepartmentRelation(UserLogon logon, String userLogin, String departmentSysname) throws CarabiException {
		logon.assertAllowedAll(new String[] {"ADMINISTRATING-USERS-EDIT", "ADMINISTRATING-DEPARTMENTS-EDIT"});
		CarabiUser user = uc.findUser(userLogin);
		Department department = departmentsPercistence.getDepartment(departmentSysname);
		Collection<Department> relatedDepartments = user.getRelatedDepartments();
		if (relatedDepartments.contains(department)) {
			relatedDepartments.remove(department);
			em.merge(user);
		}
	}
	
	public String getSchemasList(UserLogon logon) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-SCHEMAS-VIEW");
		// gets oracle databases from kernel db
		final TypedQuery query = em.createQuery(
				"select CS from ConnectionSchema CS order by CS.name", 
				ConnectionSchema.class);
		final List resultList = query.getResultList();
		close();

		// creates json object of the following form 
		//  { "connectionSchemes": [ 
		//                     { "connectionSchema", [
		//						     { "id": "" }, 
		//							 { "name", ""}, 
		//							 { "sysName", ""},
		//							 { "jndiName", ""}
		//					   ]}
		//                     ...
		//  ]}
		final JsonArrayBuilder jsonSchemas = Json.createArrayBuilder();
		final Iterator<ConnectionSchema> schemasIterator = resultList.iterator();
		while (schemasIterator.hasNext()) {
			final ConnectionSchema schema = schemasIterator.next();
			JsonObjectBuilder jsonSchemaDetails = createJsonSchema(schema);
			
			final JsonObjectBuilder jsonSchema = Json.createObjectBuilder();
			jsonSchema.add("connectionSchema", jsonSchemaDetails);
			jsonSchemas.add(jsonSchema);
		}

		// handles empty list case - just add root object "carabiusers"
		final JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("connectionSchemes", jsonSchemas);
		
		// returns results
		return result.build().toString();
	}	

	private JsonObjectBuilder createJsonSchema(final ConnectionSchema schema) {
		JsonObjectBuilder jsonSchemaDetails = Json.createObjectBuilder();
		jsonSchemaDetails.add("id", schema.getId());
		Utls.addJsonObject(jsonSchemaDetails, "name", schema.getName());
		Utls.addJsonObject(jsonSchemaDetails, "sysName", schema.getSysname());
		Utls.addJsonObject(jsonSchemaDetails, "jndiName", schema.getJNDI());
		return jsonSchemaDetails;
	}

	public String getSchema(UserLogon logon, Integer id) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-SCHEMAS-VIEW");
		final ConnectionSchema schema = em.find(ConnectionSchema.class, id);
		if (null == schema) {
			CarabiException e = new CarabiException("Cхема подключения не "
					+ "найдена по ID: " + id.toString());
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		// fill in user fields
		final JsonObjectBuilder jsonSchema = createJsonSchema(schema);
		
		// returns results
		return jsonSchema.build().toString();
	}	
	
	public Integer saveSchema(UserLogon logon, String strSchema) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-SCHEMAS-EDIT");
		// parse url string and obtain json object
		final String nonUrlStrSchema = strSchema.replace("&quot;", "\"");
		final JsonReader jsonSchemaReader = Json.createReader(new StringReader(nonUrlStrSchema));
		final JsonObject jsonSchema = jsonSchemaReader.readObject();
		
		// create or fetch schema
		ConnectionSchema schema;
		if (!jsonSchema.containsKey("id") || "".equals(Utls.getNativeJsonString(jsonSchema,"id"))) {
			schema = new ConnectionSchema();
		} else {
			int schemaId;
			try {
				 schemaId = Integer.decode(jsonSchema.getString("id"));
			} catch (NumberFormatException nfe) {
				final CarabiException e = new CarabiException(
						"Неверный формат ID. Ожидется: java.lang.Integer", nfe);
				logger.log(Level.WARNING, "" , e);
				throw e;
			}
			schema = em.find(ConnectionSchema.class, schemaId);
			close();
		}
		
		// установка полей схемы
		schema.setName(jsonSchema.getString("name"));
		schema.setSysname(jsonSchema.getString("sysName"));
		schema.setJNDI(jsonSchema.getString("jndiName"));
		
		// save user data
		schema = em.merge(schema);
		close();
		
		return schema.getId();
	}
	
	public void deleteSchema(UserLogon logon, Integer id) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-SCHEMAS-EDIT");
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "схему подключения, т.к. не задан ID удаляемой записи.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		final Query query = em.createQuery("DELETE FROM ConnectionSchema cs WHERE cs.id = :id");
		int deletedCount = query.setParameter("id", id).executeUpdate();
		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Нет записи с таким id. Ошибка удаления "
					+ "схемы подключения при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;			
		}
	}
	
	public String getCategoriesList(UserLogon logon) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-VIEW");
		final TypedQuery query = em.createQuery(
				"select Q from QueryCategory Q order by Q.name",
				QueryCategory.class);
		final List resultList = query.getResultList();
		close();
		JsonArrayBuilder categories = Json.createArrayBuilder();
		/*final JsonObjectBuilder jsonFieldsAll = Json.createObjectBuilder();
		jsonFieldsAll.add("id", -1);
		jsonFieldsAll.add("name", "Все");
		jsonFieldsAll.add("description", "Все запросы из всех категорий");
		jsonFieldsAll.addNull("parentId");
		categories.add(jsonFieldsAll);*/
		final Iterator<QueryCategory> categoryIterator = resultList.iterator();
		while (categoryIterator.hasNext()) {
			final QueryCategory queryCategory = categoryIterator.next();
			final JsonObjectBuilder jsonFields = Json.createObjectBuilder();
			jsonFields.add("id", queryCategory.getId());
			jsonFields.add("name", queryCategory.getName());
			if (queryCategory.getDescription() == null) {
				jsonFields.addNull("description");
			} else {
				jsonFields.add("description", queryCategory.getDescription());
			}
			if (queryCategory.getParentId() == null) {
				jsonFields.addNull("parentId");
			} else {
				jsonFields.add("parentId", queryCategory.getParentId());
			}
			categories.add(jsonFields);
		}
		final JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("categories", categories);
		return result.build().toString();
	}

	public Long saveCategory(UserLogon logon, String strCategory) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-EDIT");
		// log the call
		logger.log(Level.INFO, "package ru.carabi.server.kernel"
		                      +".AdminBean.saveCategory(String strCategory)"
		                      +" caller params: {0}", strCategory);

		// parse url string and obtain json object
		final String nonUrlStrCategory = strCategory.replace("&quot;", "\"");
		final JsonReader jsonCategoryReader = Json.createReader(new StringReader(nonUrlStrCategory));
		final JsonObject jsonCategory = jsonCategoryReader.readObject();

		// create or fetch category
		QueryCategory queryCategory;
		if (!jsonCategory.containsKey("id") ||
			jsonCategory.get("id").getValueType().equals(JsonValue.ValueType.NULL) ||
			"".equals(Utls.getNativeJsonString(jsonCategory,"id")))
		{
			queryCategory = new QueryCategory();
		} else {
			long queryCategoryId;
			try {
				queryCategoryId = Long.decode(jsonCategory.getString("id"));
			} catch (NumberFormatException nfe) {
				final CarabiException e = new CarabiException(
						"Неверный формат ID. Ожидется: java.lang.Integer", nfe);
				logger.log(Level.WARNING, "" , e);
				throw e;
			}
			queryCategory = em.find(QueryCategory.class, queryCategoryId);
			close();
		}

		// установка полей схемы
		queryCategory.setName(jsonCategory.getString("name"));
		queryCategory.setDescription(jsonCategory.getString("name")); // note: in the current implementation 'category name'=='category decription'. this is because the description field in becoming obsolete, as we are not using it in the interfaces.
		if (!jsonCategory.get("parentId").equals(JsonValue.NULL))
			queryCategory.setParentId(jsonCategory.getInt("parentId"));

		// save user data
		queryCategory = em.merge(queryCategory);
		close();

		return queryCategory.getId();
	}

	public Integer deleteCategory(UserLogon logon, Integer id) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-EDIT");
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "категорию запросов, т.к. не задан ID удаляемой записи.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		// check if category contains other categories
		final Query countCategoriesQuery = em.createQuery("select count(C) from QueryCategory C where C.parentId = :parentId");
		countCategoriesQuery.setParameter("parentId", id);
		if ((long) countCategoriesQuery.getSingleResult() > 0)
			return -1;
		// check if category contains queries
		final Query countQueriesQuery = em.createQuery("select count(Q) from QueryEntity Q where Q.category.id = :categoryId");
		countQueriesQuery.setParameter("categoryId", id);
		if ((long) countQueriesQuery.getSingleResult() > 0)
			return -2;
		// try to delete record
		final Query deleteQuery = em.createQuery("DELETE FROM QueryCategory qc WHERE qc.id = :id");
		int deletedCount = deleteQuery.setParameter("id", id).executeUpdate();
		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Нет записи с таким id. Ошибка удаления "
					+ "категории запросов при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		return 0;
	}

	public String getQueriesList(UserLogon logon, int categoryId) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-VIEW");
		TypedQuery query;
		if (categoryId >= 0) {
			query = em.createNamedQuery("selectCategoryQueries", QueryEntity.class);
			query.setParameter("categoryId", categoryId);
		} else {
			query = em.createNamedQuery("selectAllQueries", QueryEntity.class);
		}
		final List resultList = query.getResultList();
		close();
		
		// creates json object of the following form 
		//	    { "queries": [ 
		//                     { "query",  
		//									{ "id": "" }, 
		//									{ "category": "" }, 
		//									{ "name", ""} 
		//					   }
		//                     ...
		//  ]}
		final JsonArrayBuilder jsonQueries = Json.createArrayBuilder();
		final Iterator<QueryEntity> queriesIterator = resultList.iterator();
		while (queriesIterator.hasNext()) {
			final QueryEntity queryEntity = queriesIterator.next();
			
			final JsonObjectBuilder jsonFields = Json.createObjectBuilder();
			jsonFields.add("id", queryEntity.getId());
			jsonFields.add("category", queryEntity.getCategory().getName());
			jsonFields.add("name", queryEntity.getName());
			jsonFields.add("sysname", queryEntity.getSysname());
			jsonFields.add("isExecutable", queryEntity.getIsExecutable());
			String schemaId = queryEntity.getSchema() == null ? "" : queryEntity.getSchema().getId().toString();
			jsonFields.add("schemaId", schemaId);
			
			final JsonObjectBuilder jsonQuery = Json.createObjectBuilder();
			jsonQuery.add("query", jsonFields);
			jsonQueries.add(jsonQuery);
		}

		// handles empty list case - just add root object "carabiusers"
		final JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("queries", jsonQueries);
		
		// returns results
		return result.build().toString();
	}
	
	public String searchQueries(UserLogon logon, String condition) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-VIEW");
		TypedQuery query = em.createQuery(
				"select qe " + 
				"from QueryEntity qe " +
				"where LOWER(qe.name) like :likeCondition or LOWER(qe.sysname) like :likeCondition " + 
				"order by qe.name", 
				QueryEntity.class);
		query.setParameter("likeCondition", "%"+condition.toLowerCase()+"%");
		final List resultList = query.getResultList();
		close();
		// creates json object of the following form 
		//	    { "queries": [ 
		//                     { "query",  
		//									{ "id": "" }, 
		//									{ "category": "" }, 
		//									{ "name", ""} 
		//					   }
		//                     ...
		//  ]}
		final JsonArrayBuilder jsonQueries = Json.createArrayBuilder();
		final Iterator<QueryEntity> queriesIterator = resultList.iterator();
		while (queriesIterator.hasNext()) {
			final QueryEntity queryEntity = queriesIterator.next();
			final JsonObjectBuilder jsonFields = Json.createObjectBuilder();
			// fill in fields
			jsonFields.add("id", queryEntity.getId());
			//jsonFields.add("category", queryEntity.getCategory().getName());
			jsonFields.add("name", queryEntity.getName());
			jsonFields.add("sysname", queryEntity.getSysname());
			//jsonFields.add("isExecutable", queryEntity.getIsExecutable());
			//String schemaId = queryEntity.getSchema() == null ? "" : queryEntity.getSchema().getId().toString();
			//jsonFields.add("schemaId", schemaId);
			// add "query" object to queries array builder
			final JsonObjectBuilder jsonQuery = Json.createObjectBuilder();
			jsonQuery.add("query", jsonFields);
			jsonQueries.add(jsonQuery);
		}
		// handles empty list case - just add root object "queries"
		final JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("queries", jsonQueries);
		// returns results
		return result.build().toString();
	}
	
	public String getQuery(UserLogon logon, Long id) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-VIEW");
		final QueryEntity queryEntity = em.find(QueryEntity.class, id);
		if (queryEntity == null) {
			final CarabiException e = new CarabiException(
					"Запрос не найден по ID: " + id.toString());
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		// fill in user fields
		final JsonObjectBuilder jsonQuery = Json.createObjectBuilder();
		jsonQuery.add("id", queryEntity.getId());
		jsonQuery.add("сategoryId", queryEntity.getCategory().getId());
		jsonQuery.add("name", queryEntity.getName());
		jsonQuery.add("isExecutable", queryEntity.getIsExecutable());
		jsonQuery.add("schemaId", queryEntity.getSchema() == null ? 
				"" : queryEntity.getSchema().getId().toString());
		jsonQuery.add("text", queryEntity.getBody());
		jsonQuery.add("sysname", queryEntity.getSysname());
		
		// fill params
		final JsonArrayBuilder jsonParams = Json.createArrayBuilder();
		for (QueryParameterEntity p : queryEntity.getParameters()) {
			final JsonObjectBuilder jsonParam = Json.createObjectBuilder();
			
			jsonParam.add("id", p.getId());
			jsonParam.add("orderNumber", p.getOrdernumber());
			jsonParam.add("name", p.getName());
			jsonParam.add("type", p.getType());
			jsonParam.add("isIn", p.getIsIn());
			jsonParam.add("isOut", p.getIsOut());
			
			final JsonObjectBuilder jsonParamWrapper = Json.createObjectBuilder();
			jsonParamWrapper.add("param", jsonParam);
			jsonParams.add(jsonParamWrapper);
		}
		jsonQuery.add("params", jsonParams);
		
		// returns results
		return jsonQuery.build().toString();
	}
	
	public Long saveQuery(UserLogon logon, String strQuery) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-EDIT");
		// log the call
		logger.log(Level.INFO, "package ru.carabi.server.kernel"
		                      +".AdminBean.saveQuery(String strQuery)"
		                      +" caller params: {0}", strQuery);

		// parse url string and obtain json object
		final String nonUrlStrQuery = strQuery.replace("&quot;", "\"");
		JsonReader queryReader = Json.createReader(new StringReader(nonUrlStrQuery));
		final JsonObject jsonQuery = queryReader.readObject();
		
		String queryId = Utls.getNativeJsonString(jsonQuery,"id");// = jsonQuery.getString("id");
		QueryEntity queryEntity = EntityManagerTool.createOrFind(em, QueryEntity.class, Long.class, queryId);
		
		// получение схемы запроса
		int schemaId;
		try {
			schemaId = jsonQuery.getInt("schemaId");
		} catch (NumberFormatException nfe) {
			final CarabiException e = new CarabiException("Неверный формат "
					+ "ID схемы. Ожидется: java.lang.Integer", nfe);
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		final ConnectionSchema schema = em.find(ConnectionSchema.class, schemaId);
		
		// получение категории запроса
		long categoryId;
		try {
			categoryId = jsonQuery.getJsonNumber("categoryId").longValueExact();
		} catch (NumberFormatException nfe) {
			final CarabiException e = new CarabiException("Неверный формат "
					+ "ID категории. Ожидется: java.lang.Long", nfe);
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		final QueryCategory category = em.find(QueryCategory.class, categoryId);
		
		// установка полей запроса
		queryEntity.setCategory(category);
		queryEntity.setName(jsonQuery.getString("name"));
		queryEntity.setIsExecutable(jsonQuery.getBoolean("isExecutable"));
		queryEntity.setSchema(schema);
		queryEntity.setBody(jsonQuery.getString("text"));
		queryEntity.setSysname(jsonQuery.getString("sysname"));
		
		// удалить удаленные, создать новые объекты параметров и 
		// обновить существующие
		final JsonArray jsonParams = jsonQuery.getJsonArray("params");
		
		// удаляем все существующие параметры
		// todo: не пересоздавать параметры
		if (null == queryEntity.getId()) {
			queryEntity.setParameters(new ArrayList<QueryParameterEntity>(jsonParams.size()));
		} else {
			final Query query = em.createQuery(
					"DELETE FROM QueryParameterEntity p "
					+ "WHERE p.queryEntity.id = :id");
			query.setParameter("id", queryEntity.getId()).executeUpdate();
			queryEntity.getParameters().clear();
		}
		
		for (int i=0; i<jsonParams.size(); i++) {
			final JsonObject jsonParam = 
					jsonParams.getJsonObject(i).getJsonObject("param");
			
			final QueryParameterEntity param = new QueryParameterEntity();
			param.setOrdernumber(jsonParam.getInt("orderNumber"));
			param.setName(jsonParam.getString("name"));
			param.setType(jsonParam.getString("type"));
			param.setIsIn(jsonParam.getBoolean("isIn") ? 1 : 0);
			param.setIsOut(jsonParam.getBoolean("isOut") ? 1 : 0);
			param.setQueryEntity(queryEntity);
			queryEntity.getParameters().add(param);
		}

		
		// save user data
		queryEntity = em.merge(queryEntity);
		close();
		
		return queryEntity.getId();
	}
	
	public void deleteQuery(UserLogon logon, Long id) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-EDIT");
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "хранимый запрос, т.к. не задан ID удаляемой записи.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		final Query query = em.createQuery("DELETE FROM QueryEntity q WHERE q.id = :id");
		int deletedCount = query.setParameter("id", id).executeUpdate();
 		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Нет записи с таким id. Ошибка удаления "
					+ "хранимого запроса при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
	}
	
	public void setQueryDeprecated(UserLogon logon, Long id, boolean isDeprecated) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-EDIT");
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно обработать "
					+ "хранимый запрос, т.к. не задан ID записи.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		//final Query query = em.createNativeQuery("update ORACLE_QUERY set IS_DEPRECATED = ? where QUERY_ID = ?");
		final Query query = em.createNativeQuery( // here the hardcoded parent_id=-901 means the id of 'Удаленные' category
				"update oracle_query " +
				"set is_deprecated= ?, " +
					"category_id=(select category_id "
								+ "from carabi_kernel.query_category "
								+ "where name = 'Удаленные' and parent_id is null) " +
				"where query_id= ? "
			);
		query.setParameter(1, isDeprecated);
		query.setParameter(2, id);
		query.executeUpdate();
	}

	
	private void close() throws CarabiException {
		try {
			em.flush();
			em.clear();
		} catch (PersistenceException e) {
			logger.log(Level.SEVERE, null, e);
			throw new CarabiException(e.getMessage(), e);
		}
	}
	
	/**
	 * Создание / редактирование аватара
	 * @param logon сессия пользователя (может редактировать аватар себе или другому)
	 * @param targetUser пользователь, которому меняем аватар, если не текущему
	 * @return данные о созданном аватаре
	 * @throws CarabiException 
	 */
	public FileOnServer createUserAvatar(UserLogon logon, CarabiUser targetUser) throws CarabiException {
		CarabiUser user;
		if (targetUser == null) {
			user = logon.getUser();
		} else {
			logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
			user = targetUser;
		}
		String login = user.getLogin();
		FileOnServer avatar = user.getAvatar();
		if (avatar != null) { //Удалить старый аватар
			user.setAvatar(null);
			user = em.merge(user);
			deleteAvatar(avatar);
		}
		avatar = new FileOnServer();
		avatar.setName(login);
		avatar = em.merge(avatar);
		em.flush();
		avatar.setContentAddress(Settings.AVATARS_LOCATION + "/" + avatar.getId() + "_" + login);
		user.setAvatar(avatar);
		user = em.merge(user);
		em.flush();
		if (targetUser == null) {//если редактируем текущего пользователя -- меняем данные в сессии на обновлённые
			logon.setUser(user);
		}
		return avatar;
	}
	
	public FileOnServer refreshAvatar(FileOnServer fileMetadata) {
		return em.merge(fileMetadata);
	}
	
	private void deleteAvatar(FileOnServer avatar) {
		images.removeThumbnails(avatar, true);
		File avatarFile = new File(avatar.getContentAddress());
		avatarFile.delete();
		em.remove(em.find(FileOnServer.class, avatar.getId()));
		em.flush();
	}
	
	public void addUserRelations(CarabiUser mainUser, String relatedUsersListStr, String relationName) throws CarabiException {
		List<CarabiUser> relatedUsersList = makeRelatedUsersList(relatedUsersListStr);
		UserRelationType relationType = getUserRelationType(relationName);
		for (CarabiUser relatedUser: relatedUsersList) {
			TypedQuery<UserRelation> findUsersRelation = em.createNamedQuery("findUsersRelation", UserRelation.class);
			findUsersRelation.setParameter("mainUser", mainUser);
			findUsersRelation.setParameter("relatedUser", relatedUser);
			UserRelation relation;
			List<UserRelation> findUsersRelationResult = findUsersRelation.getResultList();
			if (findUsersRelationResult.isEmpty()) {
				relation = new UserRelation();
				relation.setMainUser(mainUser);
				relation.setRelatedUser(relatedUser);
				relation.setRelationTypes(new ArrayList<UserRelationType>());
			} else {
				relation = findUsersRelationResult.get(0);
			}
			relation.getRelationTypes().add(relationType);
			em.merge(relation);
		}
		em.flush();
		try {
			fireRelationEvent(relatedUsersListStr, relationName, mainUser, CarabiEventType.usersRelationsAdd.getCode());
		} catch (IOException ex) {
			Logger.getLogger(AdminBean.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	private void fireRelationEvent(String relatedUsersListStr, String relationName, CarabiUser mainUser, short code) throws CarabiException, IOException {
		String event = "{\"users\":" + relatedUsersListStr + ", \"relation\":";
		if (StringUtils.isEmpty(relationName)) {
			event += "null}";
		} else {
			event += "\"" + relationName + "\"}";
		}
		eventer.fireEvent("", mainUser.getLogin(), code, event);
	}

	private UserRelationType getUserRelationType(String relationName) {
		TypedQuery<UserRelationType> findRelationType = em.createNamedQuery("findRelationType", UserRelationType.class);
		findRelationType.setParameter("name", relationName);
		UserRelationType relationType;
		List<UserRelationType> findRelationTypeResult = findRelationType.getResultList();
		if (!findRelationTypeResult.isEmpty()) {
			return findRelationType.getSingleResult();
		} else if (!StringUtils.isEmpty(relationName)) {
			relationType = new UserRelationType();
			relationType.setName(relationName);
			relationType.setSysname(relationName);
			return em.merge(relationType);
		}
		return null;
	}

	/**
	 * Составление списка пользователей из Json-списка логинов
	 * @param relatedUsersListStr
	 * @return
	 * @throws CarabiException 
	 */
	private List<CarabiUser> makeRelatedUsersList(String relatedUsersListStr) throws CarabiException {
		List<String> relatedUsersLoginList = new LinkedList<>();
		try {
			JsonReader usersListReader = Json.createReader(new StringReader(relatedUsersListStr));
			JsonArray usersJsonArray = usersListReader.readArray();
			for (int i=0; i<usersJsonArray.size(); i++) {
				String relatedUser = usersJsonArray.getString(i);
				relatedUsersLoginList.add(relatedUser);
			}
		} catch (JsonException | ClassCastException e) {
			relatedUsersLoginList.add(relatedUsersListStr);
		}
		List<CarabiUser> relatedUsersList = new ArrayList<>(relatedUsersLoginList.size());
		for (String relatedUserLogin: relatedUsersLoginList) {
			CarabiUser relatedUser = uc.findUser(relatedUserLogin);
			relatedUsersList.add(relatedUser);
		}
		return relatedUsersList;
	}

	public void removeUserRelations(CarabiUser mainUser, String relatedUsersListStr, String relationName) throws CarabiException {
		List<CarabiUser> relatedUsersList = makeRelatedUsersList(relatedUsersListStr);
		UserRelationType relationType = getUserRelationType(relationName);
		for (CarabiUser relatedUser: relatedUsersList) {
			if (StringUtils.isEmpty(relationName)) {// без указания категории удаляем всё
				TypedQuery<UserRelation> deleteUsersRelation = em.createNamedQuery("deleteUsersRelation", UserRelation.class);
				deleteUsersRelation.setParameter("mainUser", mainUser);
				deleteUsersRelation.setParameter("relatedUser", relatedUser);
				deleteUsersRelation.executeUpdate();
			} else {
				TypedQuery<UserRelation> findUsersRelation = em.createNamedQuery("findUsersRelation", UserRelation.class);
				findUsersRelation.setParameter("mainUser", mainUser);
				findUsersRelation.setParameter("relatedUser", relatedUser);
				List<UserRelation> findUsersRelationResult = findUsersRelation.getResultList();
				if (!findUsersRelationResult.isEmpty()) {
					UserRelation usersRelation = findUsersRelationResult.get(0);
					usersRelation.getRelationTypes().remove(relationType);
					em.merge(usersRelation);
				}
			}
		}
		em.flush();
		try {
			fireRelationEvent(relatedUsersListStr, relationName, mainUser, CarabiEventType.usersRelationsRemove.getCode());
		} catch (IOException ex) {
			Logger.getLogger(AdminBean.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	/**
	 * Выбор редактируемого пользователя.
	 * Выдаётся пользователь текущей сессии, если не задан userLogin. Если userLogin
	 * задан -- текущий пользователь должен иметь право на его редактирование, иначе выдаётся ошибка.
	 * @param logon текущая сессия
	 * @param userLogin логин редактируемого пользователя -- должен быть пустым, если текущий пользователь не имеет прав
	 * @return
	 * @throws CarabiException 
	 */
	public CarabiUser chooseEditableUser(UserLogon logon, String userLogin) throws CarabiException {
		CarabiUser mainUser;
		if (StringUtils.isEmpty(userLogin)) {
			mainUser = logon.getUser();
		} else {
			logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
			mainUser= uc.findUser(userLogin);
		}
		return mainUser;
	}
	
	/**
	 * Дать или отнять право у пользователя.
	 * Возможна автогенерация прав для внешних приложений.
	 * @param logon сессия текущего пользователя
	 * @param user кому выдаём право
	 * @param permissionSysname название права
	 * @param isAssigned если true - выдать право, false - снять.
	* @param autocreate создать право, если его нет в БД
	* @throws ru.carabi.server.CarabiException если текущий пользователь не может выдать данное право или если (при autocreate == false) права с таким названием не существует
	 */
	public void assignPermissionForUser(UserLogon logon, CarabiUser user, String permissionSysname, boolean isAssigned, boolean autocreate) throws CarabiException {
		Permission permission = null;
		if (autocreate) {
			permission = autocreatePermission(permission, permissionSysname);
		} else {
			permission = EntityManagerTool.findBySysname(em, Permission.class, permissionSysname);
			if (permission == null) {
				throw new CarabiException("Unknown permission: " + permissionSysname);
			}
		}
		assignPermissionForUser(logon, user, permission, isAssigned);
	}

	private Permission autocreatePermission(Permission permission, String permissionSysname) throws CarabiException {
		permission = EntityManagerTool.findBySysnameOrCreate(em, Permission.class, permissionSysname);
		if (permission.getSysname() == null) {
			permission.setSysname(permissionSysname);
			permission.setName(permissionSysname);
			if (permissionSysname.contains("-")) {//sysname по традиции включает родительские sysname-ы, отделённые дефисами.
				String parentSysname = permissionSysname.substring(0, permissionSysname.lastIndexOf("-"));
				Permission parentPermission = EntityManagerTool.findBySysname(em, Permission.class, parentSysname);
				if (parentPermission != null) {
					permission.setParentPermissionId(parentPermission.getId());
				}
			}
			permission.setDescription(permissionSysname + " (autogenerated by AdminBean.autocreatePermission)");
			permission = em.merge(permission);
			close();
		}
		return permission;
	}
	
	/**
	 * Дать или отнять право у пользователя
	 * @param logon сессия текущего пользователя
	 * @param user кому выдаём право
	 * @param permission переключаемое право
	 * @param isAssigned если true - выдать право, false - снять.
	 * @throws ru.carabi.server.CarabiException если текущий пользователь не может выдать данное право
	 */
	public void assignPermissionForUser(UserLogon logon, CarabiUser user, Permission permission, boolean isAssigned) throws CarabiException {
		Integer parentPermissionId = permission.getParentPermissionId();
		if (parentPermissionId != null && isAssigned) {
			assignPermissionForUser(logon, user, EntityManagerTool.createOrFind(em, Permission.class, parentPermissionId), isAssigned);
		}
		String sql = "select * from appl_permissions.may_assign_permission(?, ?)";
		Query query = em.createNativeQuery(sql);
		query.setParameter(1, logon.getUser().getId());
		query.setParameter(2, permission.getId());
		Boolean mayAssignPermission = (Boolean)query.getSingleResult();
		if (!mayAssignPermission) {
			throw new CarabiException("Current user can not assign permission " + permission.getSysname());
		}
		if (isAssigned) {
			query = em.createNativeQuery("select count(*) from USER_HAS_PERMISSION where USER_ID = ? and PERMISSION_ID =?");
			query.setParameter(1, user.getId());
			query.setParameter(2, permission.getId());
			Number count = (Number)query.getSingleResult();
			if (count.intValue() > 0) {
				return;
			}
			query = em.createNativeQuery("insert into USER_HAS_PERMISSION(USER_ID, PERMISSION_ID) values(? ,?)");
		} else {
			query = em.createNativeQuery("delete from USER_HAS_PERMISSION where USER_ID = ? and PERMISSION_ID = ?");
		}
		query.setParameter(1, user.getId());
		query.setParameter(2, permission.getId());
		query.executeUpdate();
	}
	
	public void assignPermissionForRole(UserLogon logon, String roleSysname, String permissionSysname, boolean isAssigned, boolean autocreate) throws CarabiException {
		Permission permission = null;
		if (autocreate) {
			permission = autocreatePermission(permission, permissionSysname);
		} else {
			permission = EntityManagerTool.findBySysname(em, Permission.class, permissionSysname);
			if (permission == null) {
				throw new CarabiException("Unknown permission: " + permissionSysname);
			}
		}
		UserRole userRole = EntityManagerTool.findBySysname(em, UserRole.class, roleSysname);
		if (userRole == null) {
			throw new CarabiException("Unknown role: " + roleSysname);
		}
		assignPermissionForRole(logon, userRole, permission, isAssigned);
	}
	
	public void assignPermissionForRole(UserLogon logon, UserRole userRole, Permission permission, boolean isAssigned) throws CarabiException {
		Integer parentPermissionId = permission.getParentPermissionId();
		if (parentPermissionId != null && isAssigned) {
			assignPermissionForRole(logon, userRole, EntityManagerTool.createOrFind(em, Permission.class, parentPermissionId), isAssigned);
		}
		boolean mayAssignPermission = mayAssignPermission(logon, permission);
		if (!mayAssignPermission) {
			throw new CarabiException("Current user can not assign permission " + permission.getSysname());
		}
		Query query;
		if (isAssigned) {
			query = em.createNativeQuery("select count(*) from ROLE_HAS_PERMISSION where ROLE_ID = ? and PERMISSION_ID =?");
			query.setParameter(1, userRole.getId());
			query.setParameter(2, permission.getId());
			Number count = (Number)query.getSingleResult();
			if (count.intValue() > 0) {
				return;
			}
			query = em.createNativeQuery("insert into ROLE_HAS_PERMISSION(ROLE_ID, PERMISSION_ID) values(? ,?)");
		} else {
			query = em.createNativeQuery("delete from ROLE_HAS_PERMISSION where ROLE_ID = ? and PERMISSION_ID = ?");
		}
		query.setParameter(1, userRole.getId());
		query.setParameter(2, permission.getId());
		query.executeUpdate();
	}
	
	public void assignRoleForUser(UserLogon logon, String userLogin, String roleSysname, boolean isAssigned) throws CarabiException {
		CarabiUser user = uc.findUser(userLogin);
		UserRole role = EntityManagerTool.findBySysname(em, UserRole.class, roleSysname);
		if (role == null) {
			throw new CarabiException("Role " + roleSysname + " does not exists");
		}
		assignRoleForUser(logon, user, role, isAssigned);
	}
	
	public void assignRoleForUser(UserLogon logon, CarabiUser user, UserRole role, boolean isAssigned) throws CarabiException {
		for (Permission permission: role.getPermissions()) {
			if (!this.mayAssignPermission(logon, permission)) {
				throw new PermissionException(logon, "to assign role " + role.getSysname() + " because it has permission " + permission.getSysname());
			}
		}
		if (isAssigned) {
			user.getRoles().add(role);
		} else {
			user.getRoles().remove(role);
		}
		em.merge(user);
	}
	
	/**
	 * Копирование набора прав между ролями.
	 * @param logon сессия текущего пользователя
	 * @param originalRoleSysname роль, из которой копируем права
	 * @param newRoleSysname роль, в которую копируем права
	 * @param removeOldPermossions удалить текущие права из newRole 
	 * @throws CarabiException если не найдена оригинальная роль или текущий пользователь не может выдать или отнять требуемое право
	 */
	public void copyPermissionsFromRoleToRole(UserLogon logon, String originalRoleSysname, String newRoleSysname, boolean removeOldPermossions) throws CarabiException {
		UserRole originalRole = EntityManagerTool.findBySysname(em, UserRole.class, originalRoleSysname);
		if (originalRole == null) {
			throw new CarabiException("Original role not found");
		}
		UserRole newRole = EntityManagerTool.findBySysnameOrCreate(em, UserRole.class, newRoleSysname);
		if (newRole.getId() == null) {
			newRole.setName(newRoleSysname);
			newRole.setSysname(newRoleSysname);
			newRole = em.merge(newRole);
		}
		copyPermissionsFromRoleToRole(logon, originalRole, newRole, removeOldPermossions);
	}
	
	/**
	 * Копирование набора прав между ролями.
	 * @param logon сессия текущего пользователя
	 * @param originalRole роль, из которой копируем права
	 * @param newRole роль, в которую копируем права
	 * @param removeOldPermossions удалить текущие права из newRole 
	 * @throws CarabiException если текущий пользователь не может выдать или отнять требуемое право
	 */
	public void copyPermissionsFromRoleToRole(UserLogon logon, UserRole originalRole, UserRole newRole, boolean removeOldPermossions) throws CarabiException {
		if (removeOldPermossions) {
			for (Permission permission: newRole.getPermissions()) {
				if (!mayAssignPermission(logon, permission)) {
					throw new CarabiException("User " + logon.getUser().getLogin() + " can not disassign old permission " + permission.getSysname() + " from role " + newRole.getSysname());
				}
			}
		}
		newRole.getPermissions().clear();
		for (Permission permission: originalRole.getPermissions()) {
			if (!mayAssignPermission(logon, permission)) {
				throw new CarabiException("User " + logon.getUser().getLogin() + " can not assign new permission " + permission.getSysname() + " for role " + newRole.getSysname());
			}
			newRole.getPermissions().add(permission);
		}
		em.merge(newRole);
	}
	
	/**
	 * Копирование набора прав из роли в пользователя.
	 * @param logon сессия текущего пользователя
	 * @param originalRoleSysname роль, из которой копируем права
	 * @param userLogin пользователь, которому копируем права
	 * @param removeOldPermossions удалить текущие права у пользователя
	 * @throws CarabiException если не найдена оригинальная роль или текущий пользователь не может выдать или отнять требуемое право
	 */
	public void copyPermissionsFromRoleToUser(UserLogon logon, String originalRoleSysname, String userLogin, boolean removeOldPermossions) throws CarabiException {
		UserRole originalRole = EntityManagerTool.findBySysname(em, UserRole.class, originalRoleSysname);
		if (originalRole == null) {
			throw new CarabiException("Original role not found");
		}
		copyPermissionsFromRoleToUser(logon, originalRole, uc.findUser(userLogin), removeOldPermossions);
	}
	
	/**
	 * Копирование набора прав из роли в пользователя.
	 * @param logon сессия текущего пользователя
	 * @param originalRole роль, из которой копируем права
	 * @param user пользователь, которому копируем права
	 * @param removeOldPermossions удалить текущие права у пользователя
	 * @throws CarabiException если текущий пользователь не может выдать или отнять требуемое право
	 */
	public void copyPermissionsFromRoleToUser(UserLogon logon, UserRole originalRole, CarabiUser user, boolean removeOldPermossions) throws CarabiException {
		Query roleToUser = em.createNativeQuery("select appl_permissions.role_to_user(?, ?, ?, ?)");
		roleToUser.setParameter(1, logon.getUser().getId());
		roleToUser.setParameter(2, originalRole.getId());
		roleToUser.setParameter(3, user.getId());
		roleToUser.setParameter(4, removeOldPermossions);
		roleToUser.getSingleResult();
	}
	
	/**
	 * Копирование набора прав из пользователя в роль.
	 * @param logon сессия текущего пользователя
	 * @param userLogin пользователь, из которого копируем права
	 * @param newRoleSysname роль, куда копируем права
	 * @param removeOldPermossions удалить текущие права из роли
	 * @throws CarabiException если текущий пользователь не может выдать или отнять требуемое право
	 */
	public void copyPermissionsFromUserToRole(UserLogon logon, String userLogin, String newRoleSysname, boolean removeOldPermossions) throws CarabiException {
		UserRole newRole = EntityManagerTool.findBySysnameOrCreate(em, UserRole.class, newRoleSysname);
		if (newRole.getId() == null) {
			newRole.setName(newRoleSysname);
			newRole.setSysname(newRoleSysname);
			newRole = em.merge(newRole);
		}
		copyPermissionsFromUserToRole(logon, uc.findUser(userLogin), newRole, removeOldPermossions);
	}
	
	/**
	 * Копирование набора прав из пользователя в роль.
	 * @param logon сессия текущего пользователя
	 * @param originalUser пользователь, из которого копируем права
	 * @param role роль, куда копируем права
	 * @param removeOldPermossions удалить текущие права из роли
	 * @throws CarabiException если текущий пользователь не может выдать или отнять требуемое право
	 */
	public void copyPermissionsFromUserToRole(UserLogon logon, CarabiUser originalUser, UserRole role, boolean removeOldPermossions) throws CarabiException {
		Query userToRole = em.createNativeQuery("select appl_permissions.role_from_user(?, ?, ?, ?)");
		userToRole.setParameter(1, logon.getUser().getId());
		userToRole.setParameter(2, role.getId());
		userToRole.setParameter(3, originalUser.getId());
		userToRole.setParameter(4, removeOldPermossions);
		userToRole.getSingleResult();
	}
	
	public void setShowOnlineMode(UserLogon logon, CarabiUser user, boolean showOnline) throws CarabiException {
		if (!user.equals(logon.getUser())) {
			logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
		}
		user.setShowOnline(showOnline);
		em.merge(user);
		try {
			JsonObjectBuilder event = Json.createObjectBuilder();
			event.add("showOnline", showOnline);
			eventer.fireEvent("", user.getLogin(), CarabiEventType.toggleOnlineDisplay.getCode(), event.build().toString());
			event = Json.createObjectBuilder();
			event.add("login", user.getLogin());
			event.add("online", showOnline);
			eventer.fireEvent("", "", CarabiEventType.userOnlineEvent.getCode(), event.build().toString());
		} catch (IOException ex) {
			Logger.getLogger(AdminBean.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	/**
	 * Изменение статуса пользователя
	 * @param logon сессия текущего пользователя
	 * @param login
	 * @param statusSysname
	 * @throws CarabiException 
	 */
	public void setUserStatus(UserLogon logon, String login, String statusSysname) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
		CarabiUser user = uc.findUser(login);
		try {
			TypedQuery<UserStatus> getUserStatus = em.createNamedQuery("getUserStatus", UserStatus.class);
			getUserStatus.setParameter("sysname", statusSysname);
			UserStatus status = getUserStatus.getSingleResult();
			user.setStatus(status);
			em.merge(user);
			em.flush();
		} catch (NoResultException e) {
			throw new CarabiException("status " + statusSysname + " not found");
		}
	}

	public String getPhoneTypes() throws CarabiException {
		// read kernel db
		final TypedQuery<PhoneType> query = em.createNamedQuery("selectAllPhoneTypes", PhoneType.class);// select PT from PhoneType PT
		final List<PhoneType> phoneTypes = query.getResultList();
		close();
		// build json
		JsonArrayBuilder phoneTypesJson = Json.createArrayBuilder();
		for (PhoneType phoneType: phoneTypes) {
			JsonObjectBuilder phoneTypeJson = Json.createObjectBuilder();
			Utls.addJsonNumber(phoneTypeJson, "id", phoneType.getId());
			Utls.addJsonObject(phoneTypeJson, "name", phoneType.getName());
			Utls.addJsonObject(phoneTypeJson, "sysName", phoneType.getSysname());
			phoneTypesJson.add(phoneTypeJson);
		}
		return phoneTypesJson.build().toString();
	}
	
	/**
	 * Проверка, может ли текущий пользователь назначить или отнять данное право для других
	 * @param permission
	 * @return 
	 */
	private boolean mayAssignPermission(UserLogon logon, Permission permission) {
		String sql = "select * from appl_permissions.may_assign_permission(?, ?)";
		Query query = em.createNativeQuery(sql);
		query.setParameter(1, logon.getUser().getId());
		query.setParameter(2, permission.getId());
		return (Boolean)query.getSingleResult();
	}
	
	/**
	 * Получить список пользователей, у которых есть указанное право.
	 * Метод возвращает только пользователей, которым право выдано напрямую (без учёта ролей и allow by default).
	 * @param logon
	 * @param permissionSysame
	 * @return 
	 */
	public List<CarabiUser> getUsersHavingPermission(UserLogon logon, String permissionSysame) throws CarabiException {
		logon.assertAllowedAny(new String[] {"ADMINISTRATING-USERS-VIEW", "MANAGING-USERS-VIEW"});
		Permission permission = EntityManagerTool.findBySysname(em, Permission.class, permissionSysame);
		if (permission == null) {
			throw new CarabiException("permission " + permissionSysame + " not found");
		}
		String sql = "select carabi_user.user_id, carabi_user.login, carabi_user.firstname, carabi_user.middlename, carabi_user.lastname, carabi_user.email\n" +
				"from user_permission, user_has_permission, carabi_user\n" +
				"where user_permission.permission_id = user_has_permission.permission_id\n" +
				"and carabi_user.user_id = user_has_permission.user_id\n" +
				"and user_permission.sysname = ?";
		Query query = em.createNativeQuery(sql);
		query.setParameter(1, permissionSysame);
		List resultList = query.getResultList();
		List<CarabiUser> result = new ArrayList<>();
		for (Object data: resultList) {
			Object[] dbUser = (Object[]) data;
			CarabiUser user = new CarabiUser();
			user.setId((Long) dbUser[0]);
			user.setLogin((String) dbUser[1]);
			user.setFirstname((String) dbUser[2]);
			user.setMiddlename((String) dbUser[3]);
			user.setLastname((String) dbUser[4]);
			result.add(user);
		}
		return result;
	}
}
