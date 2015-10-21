package ru.carabi.server.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.mail.internet.MimeUtility;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
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
 * Данные о ПО, передаваемом через данный сервлет, хранятся в объектах
 * {@link SoftwareProduct} и {@link ProductVersion} 
 * @author sasha
 */
@WebServlet(name = "LoadSoftware", urlPatterns = {"/LoadSoftware"})
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
				Department destinatedForDepartment = productVersion.getDestinatedForDepartment();
				//если запрашиваемая версия предназначена не для подразделения,
				//в ктором работает текущий пользователь -- отказ
				//ToDo: Проверить, что у пользователя есть право на сам продукт
				if (destinatedForDepartment != null) {
					List<Department> userDepartmentBranch = departmentsPercistence.getDepartmentBranch(logon);
					if (!userDepartmentBranch.contains(destinatedForDepartment)) {
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

	/**
	 * Handles the HTTP <code>POST</code> method.
	 *
	 * @param request servlet request
	 * @param response servlet response
	 * @throws ServletException if a servlet-specific error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		throw new UnsupportedOperationException("Not supported yet");
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
