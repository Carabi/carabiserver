package ru.carabi.server.soap;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Сервис для управления сервером и основными данными в служебной БД
 * @author sasha<kopilov.ad@gmail.com>
 * @author misha<mikhail.bortsov@gmail.com>
 */
@WebService(serviceName = "AdminService")
public class AdminService {
	@EJB private UsersControllerBean usersController;
	@EJB private AdminBean admin;
	private static final Logger logger = CarabiLogging.getLogger(AdminService.class);
	/**
	 * Получение списка схем, доступных пользователю.
	 * @param login логин
	 * @return список псевдонимов схем
	 * @throws CarabiException если такого пользователя нет
	 */
	
	@WebMethod(operationName = "getUserAllowedSchemas")
	public List<String> getUserAllowedSchemas(
			@WebParam(name = "login") String login
		) throws CarabiException
	{
		return admin.getUserAllowedSchemas(login);
	}
	/**
	 * Получение основной схемы пользователя.
	 * @param login логин
	 * @return псевдоним основной схемы
	 * @throws CarabiException если такого пользователя нет
	 */
	public String getMainSchema(String login) throws CarabiException {
		return admin.getMainSchema(login);
	}
	
	/**
	 * Изменение основной схемы пользователя.
	 * @param login логин
	 * @param schemaAlias псевдоним новой основной схемы
	 * @throws CarabiException если такого пользователя или схемы нет
	 */
	public void setMainSchema(String login, String schemaAlias) throws CarabiException {
		admin.setMainSchema(login, schemaAlias);
	}

	/**
	 * Получение списка всех пользователей системы
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return json-строка вида:
	 * <pre>
	 *	   creates json object of the following form 
	 *	   { "carabiusers": [ 
	 * 	                      { "carabiuser",  { "id": "" }, { "name", ""} }, 
	 *	  					  ...
	 *	   ]}
	 * </pre>
	 * @throws CarabiException - ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "getUsersList")
	public String getUsersList(@WebParam(name = "token") String token) throws CarabiException {
		UserLogon logon = usersController.tokenControl(token);
		return admin.getUsersList(logon);
	}
	
	/**
	 * Получение пользователя по id 
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return JSON-строка вида:
	 * <pre>
	 *		"{
	 *			"carabiuser":{
	 *				"middleName":"Юрьевич",
	 *				"id":356,
	 *				"lastName":"Борцов",
	 *				"defaultSchemaId":127,
	 *				"login":"bortsov",
	 *				"password":"123",
	 *				"firstName":"Михаил",
	 *				"connectionSchemas":[
	 *					{"connectionSchema":{
	 *						"id":126,
	 *						"isAllowed":false,
	 *						"name":"carabi all",
	 *						"sysName":"carabi",
	 *						"jndi":"jdbc/carabi"
	 *					}},
	 *					{"connectionSchema":{
	 *						"id":173,
	 *						"isAllowed":true,
	 *						"name":"НОВОСТРОЙКИ СПБ",
	 *						"sysName":"newndv",
	 *						"jndi":"jdbc/newndv"
	 *					}},
	 *					...
	 *				]
	 *				"phones":[
	 *					{"phone":{
	 *						"id":18,
	 *						"phoneType":1,
	 *						"countryCode":,
	 *						"regionCode":,
	 *						"mainNumber":1236547,
	 *						"suffix":,
	 *						"schemaId":1,
	 *						"orderNumber":3
	 *					}},
	 *					...
	 *				]
	 *			}
	 *		}"
	 * </pre>
	 * @param id - идентификатор пользователя
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "getUser")
	public String getUser(@WebParam(name = "token") String token, @WebParam(name = "id") Long id) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		return admin.getUser(id);
	}
	
	/**
	 * Изменение пользовательских данных (по id)
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param strUser JSON-строка вида:
	 * <pre>
	 *		"{
	 *			"id":"356",
	 *			"firstName":"Михаил",
	 *			"middleName":"Юрьевич",
	 *			"lastName":"Борцов",
	 *			"login":"bortsov",
	 *			"password":"123",
	 *			"defaultSchemaId":"127",
	 *			"allowedSchemaIds":
	 *				[
	 *					"127",
	 *					"173",
	 *					...
	 *				]
	 *		 }"
	 * </pre>
	 * @return id сохраненной записи
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "saveUser")
	public Long saveUser(@WebParam(name = "token") String token, @WebParam(name = "strUser") String strUser) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		return admin.saveUser(strUser);
	}
	
	/**
	 * Удаление пользователя из БД по ID
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param id - идентификатор удаляемой записи
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "deleteUser")
	public void deleteUser(@WebParam(name = "token") String token, @WebParam(name = "id") Long id) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		admin.deleteUser(id);
	}	
	
	/**
	 * Получение списка всех схем подключений системы
	 * @param token "Токен", идентификатор регистрации в системе (выполненной через сервер приложений).
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return json-строка вида:
	 * creates json object with the following structure:
	 * <pre>
	 *  { "connectionSchemes": [ 
	 *                     { "connectionSchema", 
	 *						     { "id": "" }, 
	 *							 { "name", ""}, 
	 *							 { "sysName", ""},
	 *							 { "jndiName", ""}
	 *					   }
	 *                     ...
	 *  ]}
	 * </pre>
	 * @throws CarabiException - ошибка проверки токена, или обращения к базе данных
	 */	
	@WebMethod(operationName = "getSchemasList")
	public String getSchemasList(@WebParam(name = "token") String token) throws CarabiException {
		usersController.tokenControl(token);
		return admin.getSchemasList();
	}
	

	/**
	 * Получение полных данных схемы подключения по ее id
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param id - идентификатор схемы
	 * @return json-строка вида:
	 * <pre>
	 *		{"id":" ", "name":" ", "sysName":" ", "jndiName": " "}
	 * </pre>
	 * @throws CarabiException - не удается найти пользователя по id
	 */	
	@WebMethod(operationName = "getSchema")
	public String getSchema(@WebParam(name = "token") String token, @WebParam(name = "id") Integer id) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		return admin.getSchema(id);
	}
	
	/**
	 * Создание или обновление схемы
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param strSchema - json-строка вида:
	 * <pre>
	 *		{"id":" ", "name":" ", "sysName":" ", "jndiName": " "}
	 * </pre>
	 * Если не задано значение параметра id, то создается новый объект.
	 * @return id сохраненной записи
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "saveSchema")
	public Integer saveSchema(@WebParam(name = "token") String token, @WebParam(name = "strSchema") String strSchema) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		return admin.saveSchema(strSchema);
	}

	/**
	 * Удаление схемы из БД по ID
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param id - идентификатор удаляемой записи
	 * //@return "0" - запись удалена, "1" - запись не удалена 
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "deleteSchema")
	public void deleteSchema(@WebParam(name = "token") String token, @WebParam(name = "id") Integer id) 
			throws CarabiException 
	{
		usersController.tokenControl(token);
		admin.deleteSchema(id);
	}
	
	/**
	 * Получение списка всех категорий запросов 
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * @return json-строка вида:
	 * <pre>
	 *		{"categories":[
	 *			{
	 *				"id":12,
	 *				"name":"...",
	 *				"description":"..."
	 *			},
	 *			...
	 *		]}"
	 * </pre>
	 * @throws CarabiException - ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "getCategoriesList")
	public String getCategoriesList(@WebParam(name = "token") String token) throws CarabiException {
		usersController.tokenControl(token);
		return admin.getCategoriesList();
	}
	
	/**
	 * Добавление или изменение категории запросов.
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе.
	 * @param strCategory json категория в виде:
	 * <pre>
	 *		{"id":" ", "name":" "}
	 * </pre>
	 * Если не задано значение параметра id, то создается новый объект.
	 * @return id сохраненной категории
	 * @throws CarabiException - серверная ошибка, ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "saveCategory")
	public Long saveCategory(@WebParam(name = "token") String token, @WebParam(name = "strCategory") String strCategory) throws CarabiException {
		usersController.tokenControl(token);
		return admin.saveCategory(strCategory);
	}

	/**
	 * Удаление категории по id.
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе.
	 * @param id - идентификатор удаляемой категории
	 * @throws CarabiException - серверная ошибка, ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "deleteCategory")
	public void deleteCategory(@WebParam(name = "token") String token, @WebParam(name = "id") Integer id) throws CarabiException {
		usersController.tokenControl(token);
		admin.deleteCategory(id);
	}

	/**
	 * Получение списка запросов (всех или одной категории)
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * @param categoryId необязательный параметр, идентификатор категории запросов. если задан, то выбираются только запросы из указанной категории.
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return json-строка вида:
	 * <pre>
	 *		{"queries":[
	 *			{"query":{
	 *				"id":106,
	 *				"text":"select...",
	 *				"category":"Кат...",
	 *				"name":"...",
	 *				"schemaId":126,
	 *				"isExecutable":0 // 0-select, 1-function
	 *			}},
	 *			...
	 *		]}"
	 * </pre>
	 * @throws CarabiException - ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "getQueriesList")
	public String getQueriesList(
			@WebParam(name = "token") String token,
			@WebParam(name = "categoryId") int categoryId) throws CarabiException {
		usersController.tokenControl(token);
		return admin.getQueriesList(categoryId);
	}
	

	/**
	 * Получение полных данных запроса по его id
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * @param id идентификатор запроса
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return json-строка вида:
	 *		{"id":<>, "text":"<>", "сategory":"<>", "name":"<>", "schemaId":<>,
	 *		 "isExecutable":0, 
	 *		 "params":
	 *			[{"param": 
	 *				{"id":<>,"isOut":<>,"isIn":<>,"name":"<>",
	 *				"orderNumber":<>,"type":"<>"}},
	 *			 …
	 *			]
	 *		}
	 * @throws CarabiException - не удается найти пользователя по id
	 */	
	@WebMethod(operationName = "getQuery")
	public String getQuery(@WebParam(name = "token") String token, @WebParam(name = "id") Long id) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		return admin.getQuery(id);
	}
	
	/**
	 * Создание или обновление запроса
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param strQuery - json-строка запроса вида:
	 * <pre>
	 * </pre>
	 * @return isSuccessful
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "saveQuery")
	public Long saveQuery(@WebParam(name = "token") String token, @WebParam(name = "strQuery") String strQuery) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		return admin.saveQuery(strQuery);
	}
	
	/**
	 * Удаление пользователя из БД по ID
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param id - идентификатор удаляемой записи
	 * //@return "0" - запись удалена, "1" - запись не удалена 
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "deleteQuery")
	public void deleteQuery(@WebParam(name = "token") String token, @WebParam(name = "id") Long id) 
			throws CarabiException
	{
		usersController.tokenControl(token);
		admin.deleteQuery(id);
	}
	
	/**
	 * Создать связь между пользователями
	 * @param token токен текущего пользователя или администратора
	 * @param relatedUsersList  привязываемый пользователь (логин или JSON-массив логинов)
	 * @param mainUserLogin  редактируемый пользователь -- если задан, под токеном должен входить администратор
	 * @param relation
	 * @throws CarabiException
	 */
	@WebMethod(operationName = "addUserRelations")
	public void addUserRelations(
			@WebParam(name = "token") String token,
			@WebParam(name = "relatedUsersList") String relatedUsersList,
			@WebParam(name = "relation") String relation,
			@WebParam(name = "mainUserLogin") String mainUserLogin
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token, false) ) {
			CarabiUser mainUser = admin.chooseEditableUser(logon, mainUserLogin);
			admin.addUserRelations(mainUser, relatedUsersList, relation);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}
	
	/**
	 * Удалить связь между пользователями
	 * @param token токен текущего пользователя или администратора
	 * @param relatedUsersList  отвязываемый пользователь (логин или JSON-массив логинов)
	 * @param mainUserLogin  редактируемый пользователь -- если задан, под токеном должен входить администратор
	 * @param relation
	 * @throws CarabiException
	 */
	@WebMethod(operationName = "removeUserRelations")
	public void removeUserRelations(
			@WebParam(name = "token") String token,
			@WebParam(name = "relatedUsersList") String relatedUsersList,
			@WebParam(name = "relation") String relation,
			@WebParam(name = "mainUserLogin") String mainUserLogin
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token, false) ) {
			CarabiUser mainUser = admin.chooseEditableUser(logon, mainUserLogin);
			admin.removeUserRelations(mainUser, relatedUsersList, relation);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw e;
		}
	}

	/**
	 * Получение списка всех типов телефонов.
	 * @param token "Токен", идентификатор регистрации в системе (выполненной через сервер приложений).
	 * См.
	 * {@link ru.carabi.server.soap.GuestService},
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return json-строка вида:
	 * <pre>
	 *  { "phoneTypes": [
	 *                     { "phoneType",
	 *						     { "id": "" },
	 *							 { "name", ""},
	 *							 { "sysName", ""},
	 *					   }
	 *                     ...
	 *  ]}
	 * </pre>
	 * @throws CarabiException - ошибка проверки токена, или обращения к базе данных
	 */
	@WebMethod(operationName = "getPhoneTypes")
	public String getPhoneTypes(@WebParam(name = "token") String token) throws CarabiException {
		usersController.tokenControl(token);// check permissions
		return admin.getPhoneTypes();// read from derby
	}
}
