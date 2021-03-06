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
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.ProductionBean;

/**
 * Данные о продукте, просматриваемые на портале
 * @author sasha<kopilov.ad@gmail.com>
 */
@Named(value = "currentProduct")
@RequestScoped
public class CurrentProduct implements Serializable {
	@Inject private CurrentClient currentClient;
	
	@EJB private ProductionBean productionBean;
	private SoftwareProduct product;
	private ProductVersion lastVersion;
	
	@PostConstruct
	public void postConstruct() {
		HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
		HttpServletResponse response = (HttpServletResponse) FacesContext.getCurrentInstance().getExternalContext().getResponse();
		String productSysname = request.getParameter("product");
		product = productionBean.findProduct(productSysname);
		if (product == null) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
	}
	
	public boolean getExists() {
		return product != null;
	}
	
	public String getMetatitle() {
		if (getExists()) {
			return getName();
		} else {
			return "Not found";
		}
	}
	
	public String getName() {return product.getName();}
	public String getSysname() {return product.getSysname();}
	public String getDescription() {return product.getDescription();}
	public String getHomeUrl() {return product.getHomeUrl();}
	
	public ProductVersion getLastVersion() throws CarabiException {
		if (lastVersion == null && product != null) {
			lastVersion = productionBean.getLastVersion(currentClient.getUserLogon(), product.getSysname(), FormatTool.getDepartment(currentClient), false);
			FormatTool.correctDownloadUrl(product, lastVersion);
		}
		return lastVersion;
	}
	
	public List<ProductVersion> getVersionsList() throws CarabiException {
		List<ProductVersion> versionsList = productionBean.getVersionsList(currentClient.getUserLogon(), product.getSysname(), FormatTool.getDepartment(currentClient), false, false);
		for (ProductVersion version: versionsList) {
			FormatTool.correctDownloadUrl(product, version);
		}
		return versionsList;
	}

}
