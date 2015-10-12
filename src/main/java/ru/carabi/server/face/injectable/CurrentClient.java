package ru.carabi.server.face.injectable;

import java.io.Serializable;
import java.util.List;
import java.util.Properties;
import javax.ejb.EJB;
import javax.enterprise.context.SessionScoped;
import javax.inject.Named;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.Department;
import ru.carabi.server.kernel.DepartmentsPercistenceBean;

/**
 * Основные о клиенте, использующем сервер через браузер.
 * Это SessionScoped Bean, используемый для авторизации, объект сохраняет данные
 * (прежде всего UserLogon) между запросами. Для некешируемых данных использовать
 * RequestScoped элементы.
 * @author sasha<kopilov.ad@gmail.com>
 */

@Named(value = "currentClient")
@SessionScoped
public class CurrentClient implements Serializable {
	
	private Properties properties = new Properties();
	
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
	
	private List<Department> departmentBranch;
	public List<Department> getDepartmentBranch() {
		if (departmentBranch == null) {
			departmentBranch = departmentsPercistence.getDepartmentBranch(userLogon);
		}
		return departmentBranch;
	}
	
	String departmentBranchStr;
	public String getDepartmentBranchStr() {
		if (departmentBranchStr == null) {
			boolean first = true;
			StringBuilder stringBuilder = new StringBuilder();
			for (Department department: getDepartmentBranch()) {
				if (first) {
					first = false;
				} else {
					stringBuilder.append(" / ");
				}
				stringBuilder.append(department.getName());
			}
			departmentBranchStr = stringBuilder.toString();
		}
		return departmentBranchStr;
	}
}
