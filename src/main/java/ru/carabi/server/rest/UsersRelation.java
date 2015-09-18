/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ru.carabi.server.rest;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.enterprise.context.RequestScoped;
import javax.json.JsonObject;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * REST Web Service
 *
 * @author sasha
 */
@Path("users_relation")
@RequestScoped
public class UsersRelation {

	@Context
	private UriInfo context;
	Logger logger = CarabiLogging.getLogger(UsersRelation.class);
	@EJB private UsersControllerBean usersController;
	@EJB private AdminBean admin;
	
	/**
	 * Creates a new instance of UsersRelation
	 */
	public UsersRelation() {
	}

	/**
	 * PUT method for updating or creating an instance of UsersRelation
	 * @param content representation for the resource
	 * @return an HTTP response with content of the updated or created resource.
	 */
	@GET
	public String addRelation(
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("userLogin") String userLogin,
			@DefaultValue("") @QueryParam("relatedUser") String relatedUser,
			@DefaultValue("") @QueryParam("relation") String relation
			) {
		logger.info("relatedUser: " + relatedUser);
		String[] relatedUsersArray;
		if (relatedUser.contains(";")) {
			relatedUsersArray = relatedUser.split(";");
		} else {
			relatedUsersArray = new String[] {relatedUser};
		}
		try (UserLogon logon = usersController.tokenAuthorize(token) ) {
			CarabiUser mainUser = admin.chooseEditableUser(logon, userLogin);
			admin.addUserRelations(mainUser, Utls.parametersToJson(relatedUsersArray).build().toString(), relation);
		} catch (RegisterException e) {
			logger.log(Level.INFO, "", e);
			throw new RestException(e.getMessage(), Response.Status.UNAUTHORIZED);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
			throw new RestException(e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
		}
		return "0";
	}
	
	@DELETE
	public String removeRelation(
			@QueryParam("token") String token,
			@DefaultValue("") @QueryParam("userLogin") String userLogin,
			@DefaultValue("") @QueryParam("relatedUser") String relatedUser,
			@DefaultValue("") @QueryParam("relation") String relation
			) {
		String[] relatedUsersArray;
		if (relatedUser.contains(";")) {
			relatedUsersArray = relatedUser.split(";");
		} else {
			relatedUsersArray = new String[] {relatedUser};
		}
		try (UserLogon logon = usersController.tokenAuthorize(token) ) {
			CarabiUser mainUser = admin.chooseEditableUser(logon, userLogin);
			admin.removeUserRelations(mainUser, Utls.parametersToJson(relatedUsersArray).build().toString(), relation);
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, "", e);
		}
		return null;
	}
}
