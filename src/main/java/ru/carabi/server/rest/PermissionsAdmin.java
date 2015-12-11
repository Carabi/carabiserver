package ru.carabi.server.rest;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * Управление правами пользователей
 *
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("permissions_admin")
public class PermissionsAdmin {

	@EJB private AdminBean admin;
	@EJB private UsersControllerBean usersController;
	
	
	/**
	 * Дать или отнять право для пользователя.
	 * Параметры в JSON:
	 * login &ndash; логин пользователя, которому меняем настройки
	 * permissionSysname &ndash; право, которое даём или забираем
	 * isAssigned true &ndash; выдать право, false &ndash; забрать
	 * autocreate &ndash; создать право, если его нет в БД
	 * @param token токен авторизации
	 * @param data JSON-объект с полями login, permissionSysname [, isAssigned = true, autocreate = false]
	 */
	@POST
	@Consumes("application/json")
	public void assignPermissionForUser(
			@QueryParam("token") String token,
			JsonObject data
		) {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			String login = data.getString("login");
			String permissionSysname = data.getString("permissionSysname");
			boolean isAssigned = data.getBoolean("isAssigned", true);
			boolean autocreate = data.getBoolean("autocreate", false);
			admin.assignPermissionForUser(logon, usersController.findUser(login), permissionSysname, isAssigned, autocreate);
		} catch (CarabiException ex) {
			Logger.getLogger(PermissionsAdmin.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	/**
	 * @param content representation for the resource
	 * @return an HTTP response with content of the updated or created resource.
	 */
	@POST
	@Consumes("application/x-www-form-urlencoded")
	public void assignPermissionForUser(
			@QueryParam("token") String token,
			@FormParam("login") String login,
			@FormParam("permissionSysname") String permissionSysname,
			@FormParam("isAssigned") @DefaultValue("true") String isAssigned,
			@FormParam("autocreate") @DefaultValue("false") String autocreate
		) {
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			JsonObjectBuilder data = Json.createObjectBuilder();
			data.add("login", login);
			data.add("permissionSysname", permissionSysname);
			data.add("isAssigned", Boolean.valueOf(isAssigned));
			data.add("autocreate", Boolean.valueOf(autocreate));
			assignPermissionForUser(token, data.build());
		} catch (CarabiException ex) {
			Logger.getLogger(PermissionsAdmin.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}
