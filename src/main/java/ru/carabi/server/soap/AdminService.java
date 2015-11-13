package ru.carabi.server.soap;

import java.io.StringReader;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonReader;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.UserStatus;
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
	 * @param token
	 * @param login логин
	 * @return список псевдонимов схем
	 * @throws CarabiException если такого пользователя нет
	 */
	
	@WebMethod(operationName = "getUserAllowedSchemas")
	public List<String> getUserAllowedSchemas(
			@WebParam(name = "token") String token,
			@WebParam(name = "login") String login
	) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getUserAllowedSchemas(logon, login);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getUsersList(logon, null);
		}
	}
	
	/**
	 * Получение списка активных пользователей.
	 * Аналогично {@link #getUsersList(java.lang.String)}, но возвращает только 
	 * активных пользователей (с {@link CarabiUser#status} == "active").
	 * @param token
	 * @return 
	 * @throws CarabiException
	 */
	@WebMethod(operationName = "getActiveUsersList")
	public String getActiveUsersList(@WebParam(name = "token") String token) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getUsersList(logon, "active");
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getUser(logon, id);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			final String nonUrlNewData = strUser.replace("&quot;", "\"");
			JsonReader jsonReader = Json.createReader(new StringReader(nonUrlNewData));
			return admin.saveUser(logon, jsonReader.readObject(), true);
		}
	}
	
	/**
	 * Изменение статуса пользователя.
	 * Присвоение пользователю {@link UserStatus} с указанным наименованием
	 * @param token Токен (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * @param login логин редактируемого пользователя
	 * @param status системное наименование устанавливаемого статуса
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "setUserStatus")
	public void setUserStatus(
			@WebParam(name = "token") String token,
			@WebParam(name = "login") String login,
			@WebParam(name = "status") String status
	) throws CarabiException{
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.setUserStatus(logon, login, status);
		}
	}
	
	/**
	 * Удаление пользователя из БД
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param login логин удаляемого пользователя
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "deleteUser")
	public void deleteUser(@WebParam(name = "token") String token, @WebParam(name = "login") String login) 
			throws CarabiException
	{
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.deleteUser(logon, login);
		}
	}	
	
	/**
	 * Создать или обновить данные о роли пользователя.
	 * Параметр strRole должен иметь поля id, name, sysname, description.
	 * При отсутствии id поле sysname используется в качестве идентификатора.
	 * @param token token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе.
	 * @param strRole данные о роли пользователя в формате JSON
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "saveRole")
	public void saveRole(@WebParam(name = "token") String token, @WebParam(name = "strRole") String strRole) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			JsonReader jsonReader = Json.createReader(new StringReader(strRole));
			admin.saveRole(logon, jsonReader.readObject());
		}
	}
	
	/**
	 * Удалить данные о роли пользователя.
	 * Параметр strRole должен иметь поля id или sysname
	 * @param token token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе.
	 * @param strRole данные о роли пользователя в формате JSON
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "deleteRole")
	public void deleteRole(@WebParam(name = "token") String token, @WebParam(name = "strRole") String strRole) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			JsonReader jsonReader = Json.createReader(new StringReader(strRole));
			admin.deleteRole(logon, jsonReader.readObject());
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getSchemasList(logon);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getSchema(logon, id);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.saveSchema(logon, strSchema);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.deleteSchema(logon, id);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getCategoriesList(logon);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.saveCategory(logon, strCategory);
		}
	}

	/**
	 * Удаление категории по id.
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе.
	 * @param id - идентификатор удаляемой категории
	 * @return код ошибки: 0 - все ок, -1 - категория используется в качестве родительской, -2 - категория содержит запросы
	 * @throws CarabiException - серверная ошибка, ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "deleteCategory")
	public Integer deleteCategory(@WebParam(name = "token") String token, @WebParam(name = "id") Integer id) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.deleteCategory(logon, id);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getQueriesList(logon, categoryId);
		}
	}

	/**
	 * Поиск запросов по имени или системному имени. Нечувствителен к регистру. Ищет совпадения части имени 
	 * или системного имени запроса с условием. 
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param condition обязательный параметр, условие поиска запросов. 
	 * @return json-строка вида:
	 * <pre>
	 *		{"queries":[
	 *			{"query":{
	 *				"id":106,
	 *				"name":"...",
	 *				"sysname":"..."
	 *			}},
	 *			...
	 *		]}"
	 * </pre>
	 * @throws CarabiException - ошибка обращения к базе данных
	 */
	@WebMethod(operationName = "searchQueries")
	public String searchQueries(
			@WebParam(name = "token") String token,
			@WebParam(name = "condition") String condition) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.searchQueries(logon, condition);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.getQuery(logon, id);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			return admin.saveQuery(logon, strQuery);
		}
	}
	
	/**
	 * Удаление хранимого запроса из БД
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @param id - идентификатор удаляемой записи
	 * @throws CarabiException - не удается найти пользователя по id
	 */
	@WebMethod(operationName = "deleteQuery")
	public void deleteQuery(@WebParam(name = "token") String token, @WebParam(name = "id") Long id) 
			throws CarabiException
	{
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.deleteQuery(logon, id);
		}
	}
	
	/**
	 * Архивация / деархивация запроса. 
	 * Пометка, что его следует или не следует использовать
	 * @param token авторизационный токен
	 * @param id id запроса
	 * @param isDeprecated следует ли использовать запрос
	 * @throws CarabiException если запрос не найден или при ошибке авторизации
	 */
	@WebMethod(operationName = "setQueryDeprecated")
	public void setQueryDeprecated(
			@WebParam(name = "token") String token,
			@WebParam(name = "id") Long id,
			@WebParam(name = "isDeprecated") boolean isDeprecated
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.setQueryDeprecated(logon, id, isDeprecated);
		}
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
		try (UserLogon logon = usersController.tokenAuthorize(token) ) {
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
		try (UserLogon logon = usersController.tokenAuthorize(token) ) {
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
		return admin.getPhoneTypes();// read from kernel db
	}
	
	/**
	 * Дать или отнять право для пользователя
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * @param login логин пользователя, которому меняем настройки
	 * @param permissionSysname право, которое даём или забираем
	 * @param isAssigned true - выдать право, false - забрать
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "assignPermissionForUser")
	public void assignPermissionForUser (
			@WebParam(name = "token") String token,
			@WebParam(name = "login") String login,
			@WebParam(name = "permissionSysname") String permissionSysname,
			@WebParam(name = "isAssigned") boolean isAssigned
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.assignPermissionForUser(logon, usersController.findUser(login), permissionSysname, isAssigned);
		}
	}
	
	/**
	 * Дать или отнять право для роли
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * @param roleSysname роль, которую настраиваем
	 * @param permissionSysname право, которое даём или забираем
	 * @param isAssigned true - выдать право, false - забрать
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "assignPermissionForRole")
	public void assignPermissionForRole (
			@WebParam(name = "token") String token,
			@WebParam(name = "roleSysname") String roleSysname,
			@WebParam(name = "permissionSysname") String permissionSysname,
			@WebParam(name = "isAssigned") boolean isAssigned
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.assignPermissionForRole(logon, roleSysname, permissionSysname, isAssigned);
		}
	}
	
	@WebMethod(operationName = "copyPermissionsFromRoleToRole")
	public void copyPermissionsFromRoleToRole(
			@WebParam(name = "token") String token,
			@WebParam(name = "originalRole") String originalRole,
			@WebParam(name = "newRole") String newRole,
			@WebParam(name = "removeOldPermossions") boolean removeOldPermossions
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.copyPermissionsFromRoleToRole(logon, originalRole, newRole, removeOldPermossions);
		}
	}
	@WebMethod(operationName = "copyPermissionsFromRoleToUser")
	public void copyPermissionsFromRoleToUser(
			@WebParam(name = "token") String token,
			@WebParam(name = "originalRole") String originalRole,
			@WebParam(name = "userLogin") String userLogin,
			@WebParam(name = "removeOldPermossions") boolean removeOldPermossions
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.copyPermissionsFromRoleToUser(logon, originalRole, userLogin, removeOldPermossions);
		}
	}
	@WebMethod(operationName = "copyPermissionsFromUserToRole")
	public void copyPermissionsFromUserToRole(
			@WebParam(name = "token") String token,
			@WebParam(name = "userLogin") String userLogin,
			@WebParam(name = "newRole") String newRole,
			@WebParam(name = "removeOldPermossions") boolean removeOldPermossions
		) throws CarabiException {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			admin.copyPermissionsFromUserToRole(logon, userLogin, newRole, removeOldPermossions);
		}
	}
}
