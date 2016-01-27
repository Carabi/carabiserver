package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.Path;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Управление правами пользователей
 *
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("permissions_admin")
public class PermissionsAdmin {
	private static final Logger logger = CarabiLogging.getLogger(PermissionsAdmin.class);

	@EJB private AdminBean admin;
	@EJB private UsersControllerBean usersController;
	
	
	/**
	 * Дать или отнять право для пользователя.
	 * Параметры в JSON:
	 * login &ndash; логин пользователя, которому меняем настройки
	 * permissionSysname &ndash; право, которое даём или забираем
	 * projectSysname &ndash; проект, разрабатываемый в данный момент (под конкретного заказчика)
	 * productSysname &ndash; продукт (типовое решение), в котором используется метод
	 * isAssigned true &ndash; выдать право, false &ndash; забрать
	 * autocreate &ndash; создать право, если его нет в БД
	 * @param token токен авторизации
	 * @param data JSON-объект с полями login, permission [, project, product, isAssigned = true, autocreate = false]
	 */
	@POST
	@Consumes("application/json")
	public JsonObject assignPermissionForUser(
			@QueryParam("token") String token,
			JsonObject data
		) {
		JsonObjectBuilder result = Json.createObjectBuilder();
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			String login = data.getString("login");
			String projectSysname = data.getString("project", "");
			String productSysname = data.getString("product", "");
			String permissionSysname = data.getString("permission");
			if (!StringUtils.isEmpty(projectSysname)) {
				permissionSysname = projectSysname + "-" + permissionSysname;
			}
			if (!StringUtils.isEmpty(productSysname)) {
				permissionSysname = productSysname + "-" + permissionSysname;
			}
			boolean isAssigned = data.getBoolean("isAssigned", true);
			boolean autocreate = data.getBoolean("autocreate", false);
			admin.assignPermissionForUser(logon, usersController.findUser(login), permissionSysname, isAssigned, autocreate);
			result.add("status", "ok");
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			result.add("status", "error");
			result.add("details", ex.getMessage());
		}
		return result.build();
	}
	
	/**
	 * @param content representation for the resource
	 * @return an HTTP response with content of the updated or created resource.
	 */
	@POST
	@Consumes("application/x-www-form-urlencoded")
	public JsonObject assignPermissionForUser(
			@QueryParam("token") String token,
			@FormParam("login") String login,
			@FormParam("project") String project,
			@FormParam("product") String product,
			@FormParam("permission") String permission,
			@FormParam("isAssigned") @DefaultValue("true") String isAssigned,
			@FormParam("autocreate") @DefaultValue("false") String autocreate
		) {
		JsonObjectBuilder data = Json.createObjectBuilder();
		data.add("login", login);
		data.add("product", product);
		data.add("project", project);
		data.add("permission", permission);
		data.add("isAssigned", Boolean.valueOf(isAssigned));
		data.add("autocreate", Boolean.valueOf(autocreate));
		return assignPermissionForUser(token, data.build());
	}
}
