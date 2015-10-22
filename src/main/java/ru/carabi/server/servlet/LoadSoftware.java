package ru.carabi.server.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.mail.internet.MimeUtility;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.Permission;
import ru.carabi.server.entities.ProductVersion;
import ru.carabi.server.entities.SoftwareProduct;
import ru.carabi.server.face.injectable.CurrentClient;
import ru.carabi.server.kernel.DepartmentsPercistenceBean;
import ru.carabi.server.kernel.ProductionBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.rest.RestException;

/**
 * Скачивание и загрузка программного обеспечения.
 * Данные о ПО, передаваемом через данный сервлет, хранятся в виде объектов
 * {@link SoftwareProduct} и {@link ProductVersion} 
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebServlet(name = "LoadSoftware", urlPatterns = {"/load_software"})
public class LoadSoftware extends HttpServlet {
	
	private static final Logger logger = CarabiLogging.getLogger(LoadSoftware.class);
	@Inject private CurrentClient currentClient;
	@EJB private UsersControllerBean usersController;
	@EJB private DepartmentsPercistenceBean departmentsPercistence;
	@EJB private ProductionBean productionBean;
	
	/**
	 * Handles the HTTP <code>GET</code> method.
	 *
	 * @param request servlet request
	 * @param response servlet response
	 * @throws ServletException if a servlet-specific error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String token = null;
		
		if (currentClient.getIsAuthorized()) {
			token = currentClient.getUserLogon().getToken();
		} else {
			token = request.getParameter("token");
		}
		try (UserLogon logon = usersController.tokenAuthorize(token)){
			//загрузка по ID версии или по названию и номеру версии
			String versionIDStr = request.getParameter("versionID");
			if (versionIDStr != null) {//загрузка по ID версии 
				long versionID = Long.valueOf(versionIDStr);
				ProductVersion productVersion = productionBean.getProductVersion(versionID);
				if (productVersion == null || productVersion.getFile() == null) {
					sendError(response, HttpServletResponse.SC_NOT_FOUND, "Product with ID " + versionIDStr + " is not stored here");
					return;
				}
				//если продукт ограничен для использования и пользователь не имеет доступа к нему -- отказ
				Permission permissionToUse = productVersion.getSoftwareProduct().getPermissionToUse();
				if (permissionToUse != null) {
					if (!usersController.userHavePermission(logon, permissionToUse.getSysname())) {
						sendError(response, HttpServletResponse.SC_FORBIDDEN, "Product is not allowed for you, need permission " + permissionToUse.getSysname());
						return;
					}
				}
				//если запрашиваемая версия предназначена не для подразделения,
				//в ктором работает текущий пользователь -- отказ
				Department destinatedForDepartment = productVersion.getDestinatedForDepartment();
				if (destinatedForDepartment != null) {
					Collection<Department> relatedDepartments = logon.getUser().getRelatedDepartments();
					List<Department> userDepartmentBranch = departmentsPercistence.getDepartmentBranch(logon);
					if (!(userDepartmentBranch.contains(destinatedForDepartment) || relatedDepartments.contains(destinatedForDepartment))) {
						sendError(response, HttpServletResponse.SC_FORBIDDEN, "Product is not allowed for you, destinated for department " + destinatedForDepartment.getSysname());
						return;
					}
				}
				downloadProductVersion(productVersion, response);
			} else {//загрузка по названию и номеру версии
				String productName = request.getParameter("productName");
				if (productName == null) {
					sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Please input productName or versionID");
					return;
				}
				SoftwareProduct product = productionBean.findProduct(productName);
				if (product == null) {
					sendError(response, HttpServletResponse.SC_NOT_FOUND, "Product " + productName + " not found");
				}
				//ToDo: Проверить, что у пользователя есть право на продукт
				ProductVersion productVersion;
				String versionNumber = request.getParameter("versionNumber");
				if (versionNumber == null) {
					productVersion = productionBean.getLastVersion(logon, productName, null, false);
				} else {
					productVersion = productionBean.getProductVersion(logon, productName, versionNumber);
				}
				downloadProductVersion(productVersion, response);
			}
		} catch (NumberFormatException e) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
		} catch (CarabiException e) {
			if (e instanceof RegisterException) {
				sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
				return;
			}
			Throwable cause = e.getCause();
			if (cause != null && RestException.class.isAssignableFrom(cause.getClass())) {
				RestException restException = (RestException) cause;
				sendError(response, restException.getResponse().getStatus(), cause.getMessage());
			}
		}
	}
	
	/**
	 * Выгрузка из файла, если он есть, или отправка редиректа на сохранённый URL
	 * @param productVersion
	 * @param response
	 * @throws UnsupportedEncodingException
	 * @throws IOException 
	 */
	private void downloadProductVersion(ProductVersion productVersion, HttpServletResponse response) throws UnsupportedEncodingException, IOException {
		FileOnServer fileMetadata = productVersion.getFile();
		if (fileMetadata != null) {
			response.setHeader("Content-Disposition", "attachment; filename=\"" + MimeUtility.encodeText(fileMetadata.getName()) +"\"");
			response.setHeader("Filename-Base64", DatatypeConverter.printBase64Binary(fileMetadata.getName().getBytes("UTF-8")));
			response.setHeader("Content-Type", fileMetadata.getMimeType());
			response.setHeader("Content-Length", "" +fileMetadata.getContentLength());
			File file = new File(fileMetadata.getContentAddress());
			try (InputStream inputStream = new FileInputStream(file)) {
				Utls.proxyStreams(inputStream, response.getOutputStream());
			}
		} else if (productVersion.getDownloadUrl() != null) {
			response.sendRedirect(productVersion.getDownloadUrl());
		} else {
			logger.log(Level.WARNING, "Product version {0} was queried, but is not stored", productVersion.getId());
			sendError(response, HttpServletResponse.SC_NO_CONTENT, "Current version does not have File or URL data, sorry");
		}
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String token = null;
		
		if (currentClient.getIsAuthorized()) {
			token = currentClient.getUserLogon().getToken();
		}
		
		InputStream inputStream = null;
		String filename = null;
		String versionNumber = null;
		String singularity = null;
		String productSysname = null;
		String departmentSysname = null;
		boolean isSignificantUpdate = false;
		boolean removeOldVersions = false;
		
		request.setCharacterEncoding("UTF-8");
		boolean isMultipartContent = ServletFileUpload.isMultipartContent(request);
		if (isMultipartContent) {
			FileItemFactory factory = new DiskFileItemFactory();
			ServletFileUpload upload = new ServletFileUpload(factory);
			List<FileItem> fields;
			try {
				fields = upload.parseRequest(request);
				Iterator<FileItem> it = fields.iterator();
				while (it.hasNext()) {
					FileItem fileItem = it.next();
					if (fileItem.isFormField()) {
						switch (fileItem.getFieldName()) {
							case "token":
								if (token == null) {
									token = fileItem.getString();
								}
								break;
							case "versionNumber":
								versionNumber = fileItem.getString(); break;
							case "singularity":
								singularity = fileItem.getString(); break;
							case "productSysname":
								productSysname = fileItem.getString(); break;
							case "departmentSysname":
								departmentSysname = fileItem.getString(); break;
							case "isSignificantUpdate":
								isSignificantUpdate = !StringUtils.isEmpty(fileItem.getString()); break;
							case "removeOldVersions":
								removeOldVersions = !StringUtils.isEmpty(fileItem.getString()); break;
						}
					} else {
						inputStream = fileItem.getInputStream();
						filename = fileItem.getName();
					}
				}
				if (inputStream == null) {
					sendError(response, HttpServletResponse.SC_BAD_REQUEST, "No input file");
					return;
				}
			} catch (FileUploadException ex) {
				logger.log(Level.SEVERE, null, ex);
			}
		} else {
			inputStream = request.getInputStream();
			if (token == null) {
				token = request.getParameter("token");
			}
			filename = request.getParameter("filename");
			versionNumber = request.getParameter("versionNumber");
			singularity = request.getParameter("singularity");
			productSysname = request.getParameter("productSysname");
			departmentSysname = request.getParameter("departmentSysname");
			isSignificantUpdate = !StringUtils.isEmpty(request.getParameter("isSignificantUpdate"));
			removeOldVersions = !StringUtils.isEmpty(request.getParameter("removeOldVersions"));
		}
		if (StringUtils.isEmpty(versionNumber) || StringUtils.isEmpty(productSysname)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Not enough data (versionNumber or productSysname)");
			return;
		}
		
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			SoftwareProduct product = productionBean.findProduct(productSysname);
			if (product == null) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "Product " + productSysname + " not found");
				return;
			}
			Department departmentDestination = null;
			if (!StringUtils.isEmpty(departmentSysname)) {
				departmentDestination = departmentsPercistence.findDepartment(departmentSysname);
			}
			if (removeOldVersions) {
				productionBean.removeVersions(product, departmentDestination);
			}
			ProductVersion uploadedVersion = productionBean.uploadProductVersion(logon, product, versionNumber, inputStream, filename, singularity, isSignificantUpdate, departmentDestination);
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("status", "ok");
			result.add("uploadedVersionId", uploadedVersion.getId());
			response.getOutputStream().println(result.build().toString());
		} catch (CarabiException ex) {
			Logger.getLogger(LoadSoftware.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Returns a short description of the servlet.
	 *
	 * @return a String containing servlet description
	 */
	@Override
	public String getServletInfo() {
		return "Short description";
	}
	
	private void sendError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.getOutputStream().println(message);
	}
}
