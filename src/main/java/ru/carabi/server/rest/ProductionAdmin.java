package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.enterprise.context.RequestScoped;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.ProductionBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * REST Web Service
 *
 * @author sasha
 */
@Path("production_admin")
@RequestScoped
public class ProductionAdmin {
	private static final Logger logger = CarabiLogging.getLogger(ProductionAdmin.class);
	@EJB private UsersControllerBean usersController;
	@EJB private ProductionBean productionBean;
	
	@Context
	private UriInfo context;
	
	/**
	 * Дать (или отнять) пользователю ({@link CarabiUser}) право на использование продукта ({@link SoftwareProduct}.
	 * 
	 * @param token токен авторизации 
	 * @param data данные в JSON
	 * @return 
	 */
	@POST
	@Produces("application/json")
	@Consumes("application/json")
	@Path("allow")
	public JsonObject allowForUser(
			@QueryParam("token") String token,
			JsonObject data
		) {
		JsonObjectBuilder result = Json.createObjectBuilder();
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			String login = data.getString("login");
			String productSysname = data.getString("product");
			boolean allow = data.getBoolean("allow");
			productionBean.allowForUser(logon, productSysname, login, allow);
			result.add("status", "ok");
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			result.add("status", "error");
			result.add("details", ex.getMessage());
		}
		return result.build();
	}
	
	@POST
	@Produces("text/plain")
	@Consumes("application/x-www-form-urlencoded")
	@Path("allow")
	public String allowForUser(
			@QueryParam("token") String token,
			@FormParam("login") String login,
			@FormParam("product") String product,
			@FormParam("allow") String allow
		) {
		JsonObjectBuilder data = Json.createObjectBuilder();
		data.add("login", login);
		data.add("product", product);
		data.add("allow", Boolean.valueOf(allow));
		JsonObject result = allowForUser(token, data.build());
		return result.getString("status");
	}
}
