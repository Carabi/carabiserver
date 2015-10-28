package ru.carabi.server.face.injectable;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.enterprise.context.SessionScoped;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import ru.carabi.server.CarabiException;
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
	
	public String getAuthControl() {
		HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
		HttpServletResponse response = (HttpServletResponse) FacesContext.getCurrentInstance().getExternalContext().getResponse();
		if (!getIsAuthorized()) {
			try {
				properties.setProperty("targetPageAfterAuth", request.getRequestURI());
				response.sendRedirect(request.getContextPath()+"/auth.xhtml");
			} catch (IOException ex) {
				Logger.getLogger(CurrentProduct.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return null;
	}
	
	public boolean havePermission (String permission) {
		if (!getIsAuthorized()) {
			return false;
		}
		try {
			return userLogon.havePermission(permission);
		} catch (CarabiException ex) {
			Logger.getLogger(CurrentClient.class.getName()).log(Level.SEVERE, null, ex);
			return false;
		}
	}
	
	public boolean haveAnyPermission (String permission1, String permission2) {
		if (!getIsAuthorized()) {
			return false;
		}
		try {
			return userLogon.haveAnyPermission(permission1, permission2);
		} catch (CarabiException ex) {
			Logger.getLogger(CurrentClient.class.getName()).log(Level.SEVERE, null, ex);
			return false;
		}
	}
}
