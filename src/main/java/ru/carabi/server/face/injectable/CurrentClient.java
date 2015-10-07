package ru.carabi.server.face.injectable;

import java.io.Serializable;
import java.util.List;
import java.util.Properties;
import javax.ejb.EJB;
import javax.enterprise.context.SessionScoped;
import javax.inject.Named;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.DepartmentsPercistenceBean;
import ru.carabi.server.kernel.UsersPercistenceBean;

/**
 * Данные о клиенте, использующем сервер через браузер.
 * @author sasha
 */

@Named(value = "currentClient")
@SessionScoped
public class CurrentClient implements Serializable {
	
	private Properties properties = new Properties();
	
	@EJB private UsersPercistenceBean usersPercistence;
	@EJB private DepartmentsPercistenceBean departmentsPercistence;
	
	private UserLogon userLogon;
	
	public boolean getIsAuthorized() {
		return userLogon != null;
	}
	
	public Properties getProperties() {
		return properties;
	}
	
	public UserLogon getUserLogon() {
		return userLogon;
	}
	
	public void setUserLogon(UserLogon userLogon) {
		this.userLogon = userLogon;
	}
	
	public List<SoftwareProduct> getAvailableProduction() {
		return usersPercistence.getAvailableProduction(userLogon);
	}
	
	private List<Department> departmentBranch;
	public List<Department> getDepartmentBranch() {
		if (departmentBranch == null) {
			departmentBranch = departmentsPercistence.getDepartmentBranch(userLogon);
		}
		return departmentBranch;
	}
}
