package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.ConnectionsGateBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.UsersPercistenceBean;

/**
 * Добавление/редактирование пользователей с автогенерацией подразделений
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("users_departments_admin/{schema}")
@RequestScoped
public class UsersDepartmentsAdmin {
	
	@EJB private AdminBean admin;
	@EJB private ConnectionsGateBean cg;
	@EJB private UsersControllerBean usersController;
	@EJB private UsersPercistenceBean usersPercistence;
	
	
	@POST
	@Consumes({"application/json"})
	@Produces("text/plain")
	/**
	 * Запись или обновление данных о пользователе с передачей их JSON-потоком
	 */
	public String addUserJson(
			@QueryParam("token") String token,
			JsonObject userData,
			@PathParam("schema") String schemaName
		) {
		//Проверка клиента
		UserLogon logon = usersController.getUserLogon(token);
		if (logon == null) {
			return "Unknown token: " + token;
		}
		//Проверка схемы
		int schemaID = 0;
		ConnectionSchema connectionSchema = null;
		try {
			connectionSchema = cg.getConnectionSchemaByAlias(schemaName);
			schemaID = connectionSchema.getId();
		} catch (CarabiException ex) {
			return "Unknown schema: " + schemaName;
		} catch (Exception ex) {
			Logger.getLogger(UsersAdmin.class.getName()).log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
		String login = userData.getString("login");
		JsonObjectBuilder userDataNew = Utls.jsonObjectToBuilder(userData);
		//если редактируемый пользователь с таким логином существует -- проверка, что нет коллизии между базами
		long userID = usersPercistence.getUserID(login);
		boolean userIsNew = userID == -1;
		if (!userIsNew) {
			try {
				CarabiUser user = usersController.findUser(login);
				//пользователь из новой БД должен иметь такой же пароль, чтобы быть принятым автоматически
				if (!user.getDefaultSchema().getId().equals(schemaID) && !user.getAllowedSchemas().contains(connectionSchema) && !user.getPassword().equals(userData.getString("password"))) {
					return "user " + login + " already registered with another database";
				}
				userDataNew.add("id", ""+userID);
			} catch (CarabiException ex) {
				Logger.getLogger(UsersAdmin.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		userDataNew.add("defaultSchemaId", schemaID);
		JsonArrayBuilder allowedSchemaIds = Json.createArrayBuilder();
		allowedSchemaIds.add(schemaID);
		userDataNew.add("allowedSchemaIds", allowedSchemaIds.build());
		try {
			return admin.saveUser(logon, userDataNew.build(), userIsNew, true).toString();
		} catch (Exception ex) {
			Logger.getLogger(UsersAdmin.class.getName()).log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}
	
	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("text/plain")
	/**
	 * Запись или обновление данных о пользователе с передачей их POST или GET-параметрами
	 */
	public String addUser(
			@QueryParam("token") String token,
			@DefaultValue("") @FormParam("login") String login,
			@DefaultValue("") @FormParam("password") String password,
			@DefaultValue("") @FormParam("firstName") String firstName,
			@DefaultValue("") @FormParam("middleName") String middleName,
			@DefaultValue("") @FormParam("lastName") String lastName,
			@DefaultValue("") @FormParam("corporation_name") String corporationName,
			@DefaultValue("") @FormParam("corporation_sysname") String corporationSysname,
			@DefaultValue("") @FormParam("department_name") String departmentName,
			@DefaultValue("") @FormParam("department_sysname") String departmentSysname,
			@DefaultValue("") @FormParam("role") String role,
			@DefaultValue("") @FormParam("phones") String phones,
			@PathParam("schema") String schemaName
	) {
		JsonObjectBuilder userData = Json.createObjectBuilder();
		userData
				.add("login", login)
				.add("password", password)
				.add("firstName", firstName)
				.add("middleName", middleName)
				.add("lastName", lastName)
				.add("corporationName", corporationName)
				.add("corporationSysname", corporationSysname)
				.add("departmentName", departmentName)
				.add("departmentSysname", departmentSysname)
				.add("role", role)
				.add("phones", phones);
		return addUserJson(token, userData.build(), schemaName);
	}
	
}
