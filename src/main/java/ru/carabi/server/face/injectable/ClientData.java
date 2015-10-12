package ru.carabi.server.face.injectable;

import java.util.List;
import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.inject.Named;
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
			availableProduction = productionBean.getAvailableProduction(currentClient.getUserLogon());
		}
		return availableProduction;
	}
	
}
