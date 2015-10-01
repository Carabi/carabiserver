package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.Produces;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.Department;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * REST Service для редактирования подразделений
 *
 * @author sasha
 */
@Path("departments_admin")
public class DepartmentsAdmin {

	@Context
	private UriInfo context;
	
	@EJB private UsersControllerBean usersController;
	@EJB private AdminBean admin;

	
	@POST
	@Consumes("application/json")
	@Produces("application/json")
	public JsonObject saveDepartmentJson(
			@QueryParam("token") String token,
			JsonObject departmentData
		){
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			Department savedDepartment = admin.saveDepartment(logon, departmentData);
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("status", "ok");
			result.add("id", savedDepartment.getId());
			result.add("sysname", savedDepartment.getSysname());
			return result.build();
		} catch (Exception ex) {
			Logger.getLogger(DepartmentsAdmin.class.getName()).log(Level.SEVERE, null, ex);
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("status", "fail");
			result.add("error", ex.getMessage());
			return result.build();
		}
	}
	
	/**
	 * 
	 * @param token
	 * @param id
	 * @param name
	 * @param description
	 * @param sysname
	 * @param parent_sysname
	 * @param parent_id
	 * @return 
	 */
	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/x-www-form-urlencoded")
	public String saveDepartment(
			@QueryParam("token") String token,
			@DefaultValue("") @FormParam("id") String id,
			@DefaultValue("") @FormParam("name") String name,
			@DefaultValue("") @FormParam("description") String description,
			@DefaultValue("") @FormParam("sysname") String sysname,
			@DefaultValue("") @FormParam("parent_sysname") String parent_sysname,
			@DefaultValue("") @FormParam("parent_id") String parent_id
		) {
		JsonObjectBuilder userData = Json.createObjectBuilder();
		userData
				.add("name", name)
				.add("description", description);
		if (!StringUtils.isEmpty(id)) {
			userData.add("id", id);
		}
		if (!StringUtils.isEmpty(sysname)) {
			userData.add("sysname", sysname);
		}
		if (!StringUtils.isEmpty(parent_id)) {
			userData.add("parent_id", parent_id);
		}
		if (!StringUtils.isEmpty(parent_sysname)) {
			userData.add("parent_sysname", parent_sysname);
		}
		JsonObject savedDepartmentJson = saveDepartmentJson(token, userData.build());
		if (savedDepartmentJson.containsKey("id") && savedDepartmentJson.containsKey("sysname")) {
			return "id=" + savedDepartmentJson.getString("id") +"&sysname=" + savedDepartmentJson.getString("sysname");
		} else if (savedDepartmentJson.containsKey("error")){
			return "error: " + savedDepartmentJson.getString("error");
		} else {
			return "";
		}
	}
}
