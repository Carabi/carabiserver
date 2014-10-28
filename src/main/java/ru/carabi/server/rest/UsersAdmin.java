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
import javax.ws.rs.GET;
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

/**
 * Сервис для управления пользовательской базой из Oracle
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("users_admin/{schema}")
@RequestScoped
public class UsersAdmin {
	
	@EJB private AdminBean admin;
	@EJB private ConnectionsGateBean cg;
	@EJB private UsersControllerBean usersController;
	
	@GET
	@Produces("text/plain")
	public String getUserID(
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("login") String login
		) {
		UserLogon administrator = usersController.getUserLogon(token);
		if (administrator == null) {
			return "Unknown token: " + token;
		}
		if (!administrator.isPermanent()) {
			return "not permanent token, operation not allowed";
		}
		return "" + admin.getUserID(login);
	}
	
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
		UserLogon administrator = usersController.getUserLogon(token);
		if (administrator == null) {
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
		JsonObjectBuilder userDataNew = Utls.mapToJson(userData);
		//если редактируемый пользователь с таким логином существует -- проверка, что нет коллизии между базами
		long userID = admin.getUserID(login);
		boolean userIsNew = userID == -1;
		if (!userIsNew) {
			try {
				CarabiUser user = admin.findUser(login);
				if (!user.getDefaultSchema().getId().equals(schemaID) && !user.getAllowedSchemas().contains(connectionSchema)) {
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
			return admin.saveUser(userDataNew.build().toString(), userIsNew).toString();
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
			@DefaultValue("") @FormParam("department") String department,
			@DefaultValue("") @FormParam("role") String role,
			@PathParam("schema") String schemaName
	) {
		JsonObjectBuilder userData = Json.createObjectBuilder();
		userData
				.add("login", login)
				.add("password", password)
				.add("firstName", firstName)
				.add("middleName", middleName)
				.add("lastName", lastName)
				.add("department", department)
				.add("role", role);
		return addUserJson(token, userData.build(), schemaName);
	}
}
