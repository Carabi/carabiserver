/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package ru.carabi.server.rest;

import java.util.Set;
import javax.ws.rs.core.Application;

/**
 *
 * @author sasha
 */
@javax.ws.rs.ApplicationPath("webresources")
public class ApplicationConfig extends Application {

	@Override
	public Set<Class<?>> getClasses() {
		Set<Class<?>> resources = new java.util.HashSet<>();
		addRestResourceClasses(resources);
		return resources;
	}

	/**
	 * Do not modify addRestResourceClasses() method.
	 * It is automatically populated with
	 * all resources defined in the project.
	 * If required, comment out calling this method in getClasses().
	 */
	private void addRestResourceClasses(Set<Class<?>> resources) {
		resources.add(ru.carabi.server.rest.Authorize.class);
		resources.add(ru.carabi.server.rest.Chat.class);
		resources.add(ru.carabi.server.rest.DepartmentsAdmin.class);
		resources.add(ru.carabi.server.rest.FireEvent.class);
		resources.add(ru.carabi.server.rest.PermissionsAdmin.class);
		resources.add(ru.carabi.server.rest.ProductionAdmin.class);
		resources.add(ru.carabi.server.rest.RunStoredQuery.class);
		resources.add(ru.carabi.server.rest.UsersAdmin.class);
		resources.add(ru.carabi.server.rest.UsersDepartmentsAdmin.class);
		resources.add(ru.carabi.server.rest.UsersRelation.class);
	}
	
}
