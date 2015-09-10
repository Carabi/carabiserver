package ru.carabi.server.face.injectable;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.ProductionBean;

/**
 * Данные о продукте, просматриваемые на портале
 * @author sasha
 */
@Named(value = "currentProduct")
@RequestScoped
public class CurrentProduct implements Serializable {
	@Inject private CurrentClient currentClient;
	
	@EJB private ProductionBean productionBean;
	private SoftwareProduct product;
	private ProductVersion lastVersion;
	
	@PostConstruct
	public void CurrentProductPostConstruct() {
		HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
		HttpServletResponse response = (HttpServletResponse) FacesContext.getCurrentInstance().getExternalContext().getResponse();
		//проверка авторизации
		if (!currentClient.getIsAuthorized()) {
			try {
				response.sendRedirect("index.xhtml");
			} catch (IOException ex) {
				Logger.getLogger(CurrentProduct.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		//инициализация
		String productSysname = request.getParameter("product");
		product = productionBean.getProductInfo(productSysname);
		if (product == null) {
			
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
	}
	
	public boolean getExists() {
		return product != null;
	}
	
	public String getName() {return product.getName();}
	public String getSysname() {return product.getSysname();}
	public String getDescription() {return product.getDescription();}
	public String getHomeUrl() {return product.getHomeUrl();}
	
	public ProductVersion getLastVersion() throws CarabiException {
		if (lastVersion == null && product != null) {
			lastVersion = productionBean.getLastVersion(currentClient.getUserLogon(), product.getSysname(), getDepartment(), false);
			correctDownloadUrl(lastVersion);
		}
		return lastVersion;
	}
	
	public List<ProductVersion> getVersionsList() throws CarabiException {
		List<ProductVersion> versionsList = productionBean.getVersionsList(currentClient.getUserLogon(), product.getSysname(), getDepartment(), false, false);
		for (ProductVersion version: versionsList) {
			correctDownloadUrl(version);
		}
		return versionsList;
	}

	private void correctDownloadUrl(ProductVersion version) {
		//Если заполнено поле "где скачать" -- оставляем URL в чистом виде. Иначе, если
		//есть файл, генерируем URL с сервлетом.
		if (version.getDownloadUrl() == null && version.getFile() != null) {
			version.setDownloadUrl("LoadSoftware?productName=" + product.getSysname() + "&versionNumber=" + version.getVersionNumber());
		}
	}
	
	private String getDepartment() {
		List<Department> departmentBranch = currentClient.getDepartmentBranch();
		if (departmentBranch.isEmpty()) {
			return null;
		} else {
			return departmentBranch.get(departmentBranch.size() - 1).getSysname();
		}
	}
}
