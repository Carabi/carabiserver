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
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrBuilder;
import org.json.JSONException;
import org.json.JSONObject;
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
	/**
	 * Получение ID пользователя Carabi.
	 * @param login логин пользователя
	 * @return ID пользователя. -1, если нет пользователя с таким логином.
	 */
	public Long getUserID(String login) {
		final Query query = em.createNamedQuery("findUser");
		query.setParameter("login", login);
		final List resultList = query.getResultList();
		close();
		if (resultList.isEmpty()) {
			return -1L;
		} else {
			return (Long) resultList.get(0);
		}
	}

	/**
	 * Получение списка схем, доступных пользователю.
	 * @param login логин
	 * @return список псевдонимов схем
	 * @throws CarabiException если такого пользователя нет
	 */
	public List<String> getUserAllowedSchemas(String login) throws CarabiException {
		logger.log(Level.FINEST,
		                "package ru.carabi.server.kernel.AdminBean"
		                    +".getUserAllowedSchemas(String login)"
		                    +" caller params: {0}", 
		                new Object[]{login});  
		close();
		final CarabiUser user = findUser(login);
		final Collection<ConnectionSchema> allowedSchemas = user.getAllowedSchemas();
		List<String> schemasList = new ArrayList<String>(allowedSchemas.size());
		for (ConnectionSchema allowedSchema: allowedSchemas) {
			logger.log(Level.INFO, "{0} allowed {1} ({2}, {3})", new Object[]{login, allowedSchema.getName(), allowedSchema.getSysname(), allowedSchema.getJNDI()});
			schemasList.add(allowedSchema.getSysname());
		}
		close();
		return schemasList;
	}
	
	public String getMainSchema(String login) throws CarabiException {
		final CarabiUser user = findUser(login);
		final ConnectionSchema defaultSchema = user.getDefaultSchema();
		if (defaultSchema != null) {
			return defaultSchema.getSysname();
		} else {
			return null;
		}
	}
	
	public void setMainSchema(String login, String schemaAlias) throws CarabiException {
		CarabiUser user = findUser(login);
		final ConnectionSchema mainSchema = cg.getConnectionSchemaByAlias(schemaAlias);
		user.setDefaultSchema(mainSchema);
		em.merge(user);
		close();
	}
	
	/**
	 * Возвращает список доступных пользователей из Derby.
	 * Берутся пользователи, имеющие доступ к базе, с которой работает текущий пользователь.
	 * Результат возвращается в JSON в формате 
	 * <code>
		 { "carabiusers": [ 
		                    { "carabiuser", [ { "id": "" }, { "name", ""} ]}, 
		                      ...
		 ]}
	 * </code>
	 * @param logon сессия текущего пользователя
	 * @return Cnhjrf c Json-объектом
	 */
	public String getUsersList(UserLogon logon) {
		final Query query = em.createNamedQuery("getSchemaUsersList");
		query.setParameter("schema_id", logon.getSchema().getId());
		final List resultList = query.getResultList();
		close();
		
		final JsonArrayBuilder jsonUsers = Json.createArrayBuilder();
		final Iterator usersIterator = resultList.iterator();
		while (usersIterator.hasNext()) {
			final Object[] dbUser = (Object[]) usersIterator.next();
			final JsonObjectBuilder jsonUserDetails = Json.createObjectBuilder();
			Utls.addJsonObject(jsonUserDetails, "id", dbUser[0]);
			final StrBuilder name = new StrBuilder();
			name.setNullText("");
			name.append(dbUser[1]).append(" ").append(dbUser[2]).append(" ")
					.append(dbUser[3]);
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
	
	public String getUser(Long id) throws CarabiException {
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
		Utls.addJsonObject(jsonUserDetails, "department", carabiUser.getDepartment());
		Utls.addJsonObject(jsonUserDetails, "role", carabiUser.getRole());
		Utls.addJsonObject(jsonUserDetails, "login", carabiUser.getLogin());
		Utls.addJsonObject(jsonUserDetails, "password", carabiUser.getPassword());
		Utls.addJsonObject(jsonUserDetails, "defaultSchemaId", (null == carabiUser.getDefaultSchema()) ? "" : carabiUser.getDefaultSchema().getId());
		
		//fill in schemas list with regard to whether a schema is allowed or not
		final TypedQuery<ConnectionSchema> query = em.createNamedQuery("fullSelectAllSchemas", ConnectionSchema.class);
		final List<ConnectionSchema> connectionSchemas = query.getResultList();
		close();
		
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
		
		final JsonObjectBuilder jsonUser = Json.createObjectBuilder();
		jsonUserDetails.add("connectionSchemas", jsonConnectionSchemas);
		jsonUser.add("carabiuser", jsonUserDetails);
		
		return jsonUser.build().toString();
	}
	
	/**
	 * Редактирование или создание пользовател Carabi из Web-сервиса.
	 * @param strUser Информация о пользователе в JSON
	 * @return ID пользователя
	 * @throws CarabiException
	 */
	public Long saveUser(String strUser) throws CarabiException {
		return saveUser(strUser, true);
	}
	
	
	/**
	 * Редактирование или создание пользовател Carabi из Web-сервиса.
	 * @param strUser Информация о пользователе в JSON
	 * @param updateSchemas обновлять данные о доступе к схемам (если редактируем имевшегося пользователя из Oracle -- то нет)
	 * @return ID пользователя
	 * @throws CarabiException
	 */
	public Long saveUser(String strUser, boolean updateSchemas) throws CarabiException {
		// parse url string
		final String nonUrlNewData = strUser.replace("&quot;", "\"");
		JsonReader jsonReader = Json.createReader(new StringReader(nonUrlNewData));
		final JsonObject userDetails = jsonReader.readObject();
		
		// создание или получение пользователя
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
				
		// обновление значений полей пользователя
		user.setFirstname(userDetails.getString("firstName"));
		user.setMiddlename(userDetails.getString("middleName"));
		user.setLastname(userDetails.getString("lastName"));
		user.setLogin(userDetails.getString("login"));
		user.setPassword(userDetails.getString("password"));
		user.setDepartment(userDetails.getString("department"));
		user.setRole(userDetails.getString("role"));
		
		//обновление телефонов
		String[] phones;
		if (!StringUtils.isEmpty(userDetails.getString("phones"))) {
			if (user.getPhonesList() != null) {
				for (Phone phone: user.getPhonesList()) {//удаляем старые телефоны, если на входе есть новые
					em.remove(phone);
				}
			}
			phones = userDetails.getString("phones").split("\\|\\|");
			ArrayList<Phone> phonesList = new ArrayList<>(phones.length);
			int i = 1;
			for (String phoneStr: phones) {
				String[] phoneElements = phoneStr.split("\\|");
				Phone phone = new Phone();
				if (phoneElements.length > 0) {
					phone.parse(phoneElements[0]);
				}
				PhoneType phoneType = null;
				if (phoneElements.length > 1) {
					String phoneTypeName = phoneElements[1];
					TypedQuery<PhoneType> findPhoneType = em.createNamedQuery("findPhoneType", PhoneType.class);
					findPhoneType.setParameter("name", phoneTypeName);
					List<PhoneType> resultList = findPhoneType.getResultList();
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
				phone.setPhoneType(phoneType);
				phone.setOrdernumber(i);
				i++;
				phone.setOwner(user);
				phonesList.add(phone);
			}
			user.setPhonesList(phonesList);
		}
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
	
	public void deleteUser(Long id) throws CarabiException {
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "пользователя, т.к. не задан ID удаляемой записи");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		final Query query = em.createQuery(
			"DELETE FROM CarabiUser cs WHERE cs.id = :id");
		int deletedCount = query.setParameter("id", id).executeUpdate();
		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Ошибка удаления "
					+ "пользователя при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;			
		}		
	}		
	
	public String getSchemasList() throws JSONException {
		// gets databases from our derbi db
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
		final Collection<JSONObject> jsonSchemas = 
				new ArrayList<>(resultList.size());
		final Iterator<ConnectionSchema> schemasIterator = resultList.iterator();
		while (schemasIterator.hasNext()) {
			final ConnectionSchema schema = schemasIterator.next();
			JSONObject jsonSchemaDetails = new JSONObject();
			jsonSchemaDetails.put("id", schema.getId());
			jsonSchemaDetails.put("name", schema.getName());
			jsonSchemaDetails.put("sysName", schema.getSysname());
			jsonSchemaDetails.put("jndiName", schema.getJNDI());
			
			final JSONObject jsonSchema = new JSONObject();
			jsonSchema.put("connectionSchema", jsonSchemaDetails);
			jsonSchemas.add(jsonSchema);
		}

		// handles empty list case - just add root object "carabiusers"
		final JSONObject result = new JSONObject();
		result.put("connectionSchemes", jsonSchemas);
		
		// returns results
		return result.toString();
	}	

	public String getSchema(Integer id) throws CarabiException, JSONException {
		final ConnectionSchema schema = em.find(ConnectionSchema.class, id);
		if (null == schema) {
			CarabiException e = new CarabiException("Cхема подключения не "
					+ "найдена по ID: " + id.toString());
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		// fill in user fields
		final JSONObject jsonSchema = new JSONObject();
		jsonSchema.put("id", schema.getId());
		jsonSchema.put("name", schema.getName());
		jsonSchema.put("sysName", schema.getSysname());
		jsonSchema.put("jndiName", schema.getJNDI());
		
		// returns results
		return jsonSchema.toString();
	}	
	
	public Integer saveSchema(String strSchema) 
			throws CarabiException, JSONException 
	{
		// parse url string
		final String nonUrlStrSchema = strSchema.replace("&quot;", "\"");
		final JSONObject jsonSchema = new JSONObject(nonUrlStrSchema);
		
		// создание или получение схемы 
		ConnectionSchema schema;
		if ("".equals(jsonSchema.get("id"))) {
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
		
		// обновление значений полей пользователя
		schema.setName(jsonSchema.getString("name"));
		schema.setSysname(jsonSchema.getString("sysName"));
		schema.setJNDI(jsonSchema.getString("jndiName"));
		
		// save user data
		schema = em.merge(schema);
		close();
		
		return schema.getId();
	}
	
	public void deleteSchema(Integer id) throws CarabiException
	{
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "схему подключения, т.к. не задан ID удаляемой записи");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		final Query query = em.createQuery(
			"DELETE FROM ConnectionSchema cs WHERE cs.id = :id");
		int deletedCount = query.setParameter("id", id).executeUpdate();
		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Ошибка удаления "
					+ "схемы подключения при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;			
		}
	}
	
	public String getCategoriesList() {
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

	public String getQueriesList(int categoryId) throws CarabiException {
		// gets databases from our derbi db
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
	
	public String getQuery(Long id) throws CarabiException {
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
	
	public Long saveQuery(String strQuery) throws CarabiException {
		logger.log(Level.INFO, "package ru.carabi.server.kernel"
		                      +".AdminBean.saveQuery(String strQuery)"
		                      +" caller params: {0}", strQuery);

		// parse url string
		final String nonUrlStrQuery = strQuery.replace("&quot;", "\"");
		JsonReader queryReader = Json.createReader(new StringReader(nonUrlStrQuery));
		final JsonObject jsonQuery = queryReader.readObject();
		
		// получение или создание запроса
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
		
		// обновление полей запроса
		queryEntity.setCategory(category);
		queryEntity.setName(jsonQuery.getString("name"));
		queryEntity.setIsExecutable(jsonQuery.getInt("isExecutable"));
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
	
	public void deleteQuery(Long id) throws CarabiException {
		if (null == id) {
			final CarabiException e = new CarabiException("Невозможно удалить "
					+ "хранимый запрос, т.к. не задан ID удаляемой записи");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		
		final Query query = em.createQuery(
			"DELETE FROM QueryEntity q WHERE q.id = :id");
		int deletedCount = query.setParameter("id", id).executeUpdate();
 		if (deletedCount != 1) {
			final CarabiException e = new CarabiException("Ошибка удаления "
					+ "хранимого запроса при выполнении JPA-запроса.");
			logger.log(Level.WARNING, "" , e);
			throw e;
		}		
	}
	
	public CarabiUser findUser(String login) throws CarabiException {
		try {
			TypedQuery<CarabiUser> activeUser = em.createNamedQuery("getUserInfo", CarabiUser.class);
			activeUser.setParameter("login", login);
			CarabiUser user = activeUser.getSingleResult();
			return user;
		} catch (NoResultException ex) {
			final CarabiException e = new CarabiException("No user with login " 
					+ login);
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
	}
		
	private void close() {
		em.flush();
		em.clear();
	}
	
	public FileOnServer createUserAvatar(String login) throws CarabiException {
		CarabiUser user = findUser(login);
		FileOnServer avatar = user.getAvatar();
		if (avatar != null) { //Удалить старый аватар
			user.setAvatar(null);
			user = em.merge(user);
			File avatarFile = new File(avatar.getContentAddress());
			avatarFile.delete();
			em.remove(avatar);
		}
		avatar = new FileOnServer();
		avatar.setName(login);
		avatar = em.merge(avatar);
		em.flush();
		avatar.setContentAddress(Settings.AVATARS_LOCATION + "/" + avatar.getId() + "_" + login);
		user.setAvatar(avatar);
		em.merge(user);
		em.flush();
		return avatar;
	}
	
	public FileOnServer refreshAvatar(FileOnServer fileMetadata) {
		return em.merge(fileMetadata);
	}
	
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
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
			CarabiUser relatedUser = findUser(relatedUserLogin);
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
			if (!logon.isPermanent()) {
				throw new CarabiException("You can not edit another user");
			}
			mainUser= findUser(userLogin);
		}
		return mainUser;
	}
}
