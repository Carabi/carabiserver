package ru.carabi.server.rest;

import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.PathParam;
import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.enterprise.context.RequestScoped;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceContext;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.UserLogon;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.GuestBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.EventerBean;

/**
 * 
 *
 * @author sasha
 */
@Path("authorize/{schema}")
@RequestScoped
public class Authorize {

	@EJB
	private AdminBean admin;
	@EJB
	private GuestBean guest;
	@EJB
	private EventerBean eventer;
	@EJB
	private UsersControllerBean usersController;
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	@Context
	private UriInfo context;

	/**
	 * Creates a new instance of Authorize
	 */
	public Authorize() {
	}

	@POST
	@Produces("text/plain")
	public String authorize (
			@PathParam("schema") String schemaName,
			@FormParam("adminToken") String adminToken,
			@FormParam("login") String login,
			@FormParam("password") String password,
			@FormParam("requireSession") boolean requireSession,
			@DefaultValue("") @FormParam("clientIp") String clientIp
		) {
		UserLogon administrator = usersController.getUserLogon(adminToken);
		if (administrator == null) {
			return "Unknown token: " + adminToken;
		}
		if (!administrator.isPermanent()) {
			return "not permanent token, operation not allowed";
		}
		try {
			CarabiUser user;
			try {
				user = admin.findUser(login);
			} catch (CarabiException ex) {
				Logger.getLogger(Authorize.class.getName()).log(Level.INFO, "No user {0} in Derby, create!", login);
				user = new CarabiUser();
				user.setLogin(login);
				user.setPassword(password);
				
				final TypedQuery<ConnectionSchema> allowedSchemasQuery =
						em.createQuery(
								"select cs from ConnectionSchema cs where cs.sysname = :schemaName",
								ConnectionSchema.class);
				allowedSchemasQuery.setParameter("schemaName", schemaName);
				final List<ConnectionSchema> allowedSchemas =
						allowedSchemasQuery.getResultList();
				
				user.setAllowedSchemas(allowedSchemas);
				user = em.merge(user);
			}
			Holder<String> gettingToken = new Holder();
			guest.registerUserLight(user, password, requireSession, getConnectionProperties(clientIp), new Holder(schemaName ), gettingToken);
			return "{\"token\":\"" + gettingToken.value + "\", \"eventer_token\":\"" + eventer.getEventerToken(gettingToken.value) + "\"}";
		} catch (CarabiException ex) {
			Logger.getLogger(Authorize.class.getName()).log(Level.SEVERE, null, ex);
			return ex.getMessage();
		}
	}

	private Properties getConnectionProperties(String greyIP) {
		Properties connectionProperties = new Properties();
		if (greyIP == null) {
			greyIP = "null";
		}
		connectionProperties.setProperty("ipAddrGrey", greyIP);
		connectionProperties.setProperty("ipAddrWhite", "Oracle");
		String serverIpPort = "";
		try {
			javax.naming.Context initialContext = new InitialContext();
			serverIpPort = (String) initialContext.lookup("jndi/ServerWhiteAddress");
		} catch (NamingException ex) {
			//serverIpPort = context. + ":" + req.getLocalPort();
		}
		connectionProperties.setProperty("serverContext", serverIpPort + context.getPath());
		return connectionProperties;
	}
	
	@GET
	@Produces("text/plain")
	public String unauthorize (@QueryParam("token") String token) {
		usersController.removeUser(token, true);
		return token;
	}
}
