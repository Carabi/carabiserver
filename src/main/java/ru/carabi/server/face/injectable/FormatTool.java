package ru.carabi.server.face.injectable;

import java.util.List;
import javax.ejb.EJB;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import ru.carabi.server.CarabiException;
import ru.carabi.server.EntityManagerTool;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.entities.Publication;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.kernel.ProductionBean;

/**
 * Методы для форматирования данных при выводе в Face
 * @author sasha<kopilov.ad@gmail.com>
 */
@Named(value = "formatTool")
@Dependent
public class FormatTool {
	@Inject private CurrentClient currentClient;
	@EJB private ProductionBean productionBean;
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	public ProductVersion getLastVersion(SoftwareProduct product) throws CarabiException {
		ProductVersion lastVersion = productionBean.getLastVersion(currentClient.getUserLogon(), product.getSysname(), getDepartment(currentClient), false);
		correctDownloadUrl(product, lastVersion);
		return lastVersion;
	}
	
	protected static void correctDownloadUrl(SoftwareProduct product, ProductVersion version) {
		//Если заполнено поле "где скачать" -- оставляем URL в чистом виде. Иначе, если
		//есть файл, генерируем URL с сервлетом.
		if (version != null && version.getDownloadUrl() == null && version.getFile() != null) {
			version.setDownloadUrl("LoadSoftware?productName=" + product.getSysname() + "&versionNumber=" + version.getVersionNumber());
		}
	}
	
	protected static String getDepartment(CurrentClient currentClient) {
		List<Department> departmentBranch = currentClient.getDepartmentBranch();
		if (departmentBranch.isEmpty()) {
			return null;
		} else {
			return departmentBranch.get(departmentBranch.size() - 1).getSysname();
		}
	}
	
	public String formatVersionFileSize(ProductVersion version) {
		if (version == null || version.getFile() == null) {
			return "";
		}
		Long contentLength = version.getFile().getContentLength();
		if (contentLength == null) { //при получении списка версий через PL/pgSQL подробные данные о файлах не вносятся
			FileOnServer file = new EntityManagerTool<FileOnServer, Long>().createOrFind(em, FileOnServer.class, version.getFile().getId());
			contentLength = file.getContentLength();
		}
		return Utls.formatContentLength(contentLength);
	}
	
	public String formatVersionIssueDate(ProductVersion version) {
		if (version == null || version.getIssueDate() == null) {
			return "";
		}
		return me.lima.ThreadSafeDateParser.format(version.getIssueDate(), "dd.MM.yyyy");
	}
	
	public String formatPublicationFileSize(Publication publication) {
		if (publication == null || publication.getAttachment()== null) {
			return "";
		}
		Long contentLength = publication.getAttachment().getContentLength();
		return Utls.formatContentLength(contentLength);
	}
	
	public String formatPublicationIssueDate(Publication publication) {
		if (publication == null || publication.getIssueDate() == null) {
			return "";
		}
		return me.lima.ThreadSafeDateParser.format(publication.getIssueDate(), "dd.MM.yyyy");
	}
	
	public String formatPublicationDownloadUrl(Publication publication) {
		if (publication != null) {
			return "LoadPublication?publication_id=" + publication.getId();
		} else {
			return "";
		}
	}
}
