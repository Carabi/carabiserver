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
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrBuilder;
import ru.carabi.libs.CarabiEventType;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.Phone;
import ru.carabi.server.entities.PhoneType;
import ru.carabi.server.entities.QueryCategory;
import ru.carabi.server.entities.QueryEntity;
import ru.carabi.server.entities.QueryParameterEntity;
import ru.carabi.server.entities.UserRelation;
import ru.carabi.server.entities.UserRelationType;
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
	
	/**
	 * Получение списка схем, доступных пользователю.
	 * @param logon текущий пользователь
	 * @param login логин
	 * @return список псевдонимов схем
	 * @throws CarabiException если такого пользователя нет или текущий пользователь не имеет право смотреть других
	 */
	public List<String> getUserAllowedSchemas(UserLogon logon, String login) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-USERS-VIEW");
		
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
		logon.assertAllowed("ADMINISTRATING-USERS-VIEW");
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
	 * Редактирование или создание пользовател Carabi из Web-сервиса.
	 * @param strUser Информация о пользователе в JSON
	 * @return ID пользователя
	 * @throws CarabiException
	 */
	public Long saveUser(UserLogon logon, String strUser) throws CarabiException {
		return saveUser(logon, strUser, true);
	}
	
	
	/**
	 * Редактирование или создание пользовател Carabi из Web-сервиса.
	 * @param strUser Информация о пользователе в JSON
	 * @param updateSchemas обновлять данные о доступе к схемам (если редактируем имевшегося пользователя из Oracle -- то нет)
	 * @return ID пользователя
	 * @throws CarabiException
	 */
	public Long saveUser(UserLogon logon, String strUser, boolean updateSchemas) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-USERS-EDIT");
		// parse url string and obtain json object
		final String nonUrlNewData = strUser.replace("&quot;", "\"");
		JsonReader jsonReader = Json.createReader(new StringReader(nonUrlNewData));
		final JsonObject userDetails = jsonReader.readObject();
		
		// create or fetch user
		CarabiUser user;
		if (!userDetails.containsKey("id") || "".equals(Utls.getNativeJsonString(userDetails,"id"))) {
			user = new CarabiUser();
		} else {
			Long userId;
			try {
				userId = Long.decode(Utls.getNativeJsonString(userDetails,"id"));
			} catch (NumberFormatException nfe) {
				final CarabiException e = new CarabiException(
						"Неверный формат ID пользователя. "
						+ "Ожидется: java.lang.Long", nfe);
				logger.log(Level.WARNING, "" , e);
				throw e;
			}
			user = em.find(CarabiUser.class, userId);
		}
				
		// set simple user fields
		user.setFirstname(userDetails.getString("firstName"));
		user.setMiddlename(userDetails.getString("middleName"));
		user.setLastname(userDetails.getString("lastName"));
		user.setLogin(userDetails.getString("login"));
		user.setPassword(userDetails.getString("password"));
		user.setCarabiDepartment(userDetails.getString("department"));
		user.setCarabiRole(userDetails.getString("role"));
		
		// update phones
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
			Phone phone = new Phone();
			if (phoneElements.length > 0) {
				phone.parse(phoneElements[0]);
			}
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
		final JsonObjectBuilder jsonFieldsAll = Json.createObjectBuilder();
		jsonFieldsAll.add("id", -1);
		jsonFieldsAll.add("name", "Все");
		jsonFieldsAll.add("description", "Все запросы из всех категорий");
		categories.add(jsonFieldsAll);
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
		queryCategory.setDescription(jsonCategory.getString("name")); // note: in the current implementation category name== category decription.
			// this is because the description field in becoming obsolete, as we are not using it in the interfaces.

		// save user data
		queryCategory = em.merge(queryCategory);
		close();

		return queryCategory.getId();
	}

	public void deleteCategory(UserLogon logon, Integer id) throws CarabiException {
		logon.assertAllowed("ADMINISTRATING-QUERIES-EDIT");
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "категорию запросов, т.к. не задан ID удаляемой записи.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}

		final Query query = em.createQuery("DELETE FROM QueryCategory qc WHERE qc.id = :id");
		int deletedCount = query.setParameter("id", id).executeUpdate();
		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Нет записи с таким id. Ошибка удаления "
					+ "категории запросов при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
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
		final JsonArrayBuilder jsonQueries = 
				Json.createArrayBuilder();
		final Iterator<QueryEntity> schemasIterator = resultList.iterator();
		while (schemasIterator.hasNext()) {
			final QueryEntity queryEntity = schemasIterator.next();
			
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
		
		// create or fetch query
		QueryEntity queryEntity;
		if (!jsonQuery.containsKey("id")) {
			queryEntity = new QueryEntity();
		} else {
			// получение запроса 
			long queryId;
			try {
				queryId = jsonQuery.getJsonNumber("id").longValueExact();
			} catch (NumberFormatException nfe) {
				final CarabiException e = new CarabiException("Неверный формат ID. "
						+ "Ожидется: java.lang.Long", nfe);
				logger.log(Level.WARNING, "" , e);
				throw e;
			}
			
			queryEntity = em.find(QueryEntity.class, 
				queryId);
		}
		
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
		
		final Query query = em.createNativeQuery("update ORACLE_QUERY set IS_DEPRECATED = ? where QUERY_ID = ?");
		query.setParameter(1, isDeprecated);
		query.setParameter(2, id);
		query.executeUpdate();
	}

	
	private void close() {
		em.flush();
		em.clear();
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
		//		ctx.setRollbackOnly();
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
}
