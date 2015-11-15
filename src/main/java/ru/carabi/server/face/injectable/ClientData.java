package ru.carabi.server.face.injectable;

import java.util.ArrayList;
import java.util.List;
import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.inject.Named;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.Publication;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.ProductionBean;

/**
 * Доступ к данным о клиенте. Используется для доступа к некешируемым между запросами данным
 * (которые не надо хранить в SessionScoped {@link CurrentClient})
 * @author sasha<kopilov.ad@gmail.com>
 */
@Named(value = "clientData")
@RequestScoped
public class ClientData {
	@Inject private CurrentClient currentClient;
	
	@EJB private ProductionBean productionBean;
	
	private List<Publication> availablePublication;
	public List<Publication> getAvailablePublication() {
		if (availablePublication == null) {
			availablePublication = productionBean.getAvailablePublication(currentClient.getUserLogon());
		}
		return availablePublication;
	}
	
	private List<SoftwareProduct> availableProduction;
	public List<SoftwareProduct> getAvailableProduction() {
		if (availableProduction == null) {
			availableProduction = productionBean.getAvailableProduction(currentClient.getUserLogon(), false, false);
		}
		return availableProduction;
	}
	
	private List<Department> availableDepartments;
	public List<Department> getAvailableDepartments() {
		if (availableDepartments == null) {
			availableDepartments = new ArrayList<>();
			CarabiUser user = currentClient.getUserLogon().getUser();
			addNotEmptyDepartment(availableDepartments, user.getDepartment());
			addNotEmptyDepartment(availableDepartments, user.getCorporation());
			for (Department relatedDepartment: user.getRelatedDepartments()) {
				addNotEmptyDepartment(availableDepartments, relatedDepartment);
			}
		}
		return availableDepartments;
	}

	private void addNotEmptyDepartment(List<Department> list, Department department) {
		if (department != null && !list.contains(department)) {
			list.add(department);
		}
	}
	
	public boolean productionIsAllowed(String productionName) {
		return productionBean.productionIsAllowed(currentClient.getUserLogon(), productionName);
	}
}
