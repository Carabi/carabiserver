package ru.carabi.server.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.mail.internet.MimeUtility;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
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
import ru.carabi.server.EntityManagerTool;
import ru.carabi.server.RegisterException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Department;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.Publication;
import ru.carabi.server.face.injectable.CurrentClient;
import ru.carabi.server.kernel.ProductionBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.UsersPercistenceBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Загрузка и выгрузка публикаций
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebServlet(name = "LoadPublication", urlPatterns = {"/load_publication"})
public class LoadPublication extends HttpServlet {
	private static final Logger logger = CarabiLogging.getLogger(LoadAvatar.class);
	
	@Inject private CurrentClient currentClient;
	@EJB private UsersControllerBean usersController;
	@EJB private ProductionBean productionBean;
	@EJB private UsersPercistenceBean usersPercistence;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String publicationIdStr = request.getParameter("publication_id");
		Long publicationId;
		//валидация ID
		if (StringUtils.isEmpty(publicationIdStr)) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Parameter publication_id need");
			return;
		}
		try {
			publicationId = Long.valueOf(publicationIdStr);
		} catch (NumberFormatException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Parameter publication_id is invalid");
			return;
		}
		//token из сессии если есть или из параметров
		String token;
		if (currentClient.getIsAuthorized()) {
			token = currentClient.getUserLogon().getToken();
		} else {
			token = request.getParameter("token");
		}
		
		try (UserLogon logon = usersController.tokenAuthorize(token)){
			Publication publication = productionBean.getPublication(publicationId);
			if (productionBean.allowedForUser(logon.getUser(), null, publication)) {
				downloadPublication(publication, response);
			} else {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "User " + logon.getUser().getLogin() + " is not allowed to publication " + publicationIdStr);
			}
		} catch (RegisterException ex) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, ex.getMessageCode().name());
		} catch (CarabiException ex) {
			Logger.getLogger(LoadPublication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	private void downloadPublication(Publication publication, HttpServletResponse response) throws UnsupportedEncodingException, FileNotFoundException, IOException {
		FileOnServer attachment = publication.getAttachment();
		if (attachment != null) {
			response.setHeader("Content-Disposition", "attachment; filename=\"" + MimeUtility.encodeText(attachment.getName()) +"\"");
			response.setHeader("Filename-Base64", DatatypeConverter.printBase64Binary(attachment.getName().getBytes("UTF-8")));
			response.setHeader("Content-Type", attachment.getMimeType());
			response.setHeader("Content-Length", "" +attachment.getContentLength());
			File file = new File(attachment.getContentAddress());
			try (InputStream inputStream = new FileInputStream(file)) {
				Utls.proxyStreams(inputStream, response.getOutputStream());
			}
		}
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String token = null;
		
		if (currentClient.getIsAuthorized()) {
			token = currentClient.getUserLogon().getToken();
		}
		
		InputStream inputStream = null;
		String filename = null;
		String name = null;
		String description = null;
		String loginReceiver = null;
		int departmentDestinationId = -1;
		boolean isCommon = false;
		
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
							case "name":
								name = fileItem.getString(); break;
							case "description":
								description = fileItem.getString(); break;
							case "loginReceiver":
								loginReceiver = fileItem.getString(); break;
							case "departmentDestinationId":
								departmentDestinationId = Integer.parseInt(fileItem.getString()); break;
							case "isCommon":
								isCommon = !StringUtils.isEmpty(fileItem.getString()); break;
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
			name = request.getParameter("name");
			description = request.getParameter("description");
			loginReceiver = request.getParameter("loginReceiver");
			String departmentDestinationIdStr = request.getParameter("departmentDestinationId");
			if (!StringUtils.isEmpty(departmentDestinationIdStr)) {
				departmentDestinationId = Integer.parseInt(departmentDestinationIdStr);
			}
			isCommon = !StringUtils.isEmpty(request.getParameter("isCommon"));
		}
		if (!isCommon && StringUtils.isEmpty(loginReceiver) && departmentDestinationId < 0) { //Если публикация не общедоступная -- должен быть задан адресат
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "No receiver (user, department or everybody)");
			return;
		}
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			CarabiUser receiver = null;
			if (!StringUtils.isEmpty(loginReceiver)) {
				receiver = usersPercistence.findUser(loginReceiver);
			}
			Department departmentDestination = null;
			if (departmentDestinationId >= 0) {
				EntityManagerTool<Department, Integer> entityManagerTool = new EntityManagerTool<>();
				departmentDestination = entityManagerTool.createOrFind(em, Department.class, departmentDestinationId);
			}
			Publication createdPublication = productionBean.uploadPublication(logon, name, description, inputStream, filename, receiver, departmentDestination, isCommon);
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("status", "ok");
			result.add("createdPublicationId", createdPublication.getId());
			response.getOutputStream().println(result.build().toString());
		} catch (CarabiException ex) {
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
			Logger.getLogger(LoadPublication.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}

	/**
	 * Returns a short description of the servlet.
	 *
	 * @return a String containing servlet description
	 */
	@Override
	public String getServletInfo() {
		return "Publications loading";
	}
	
	private void sendError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		JsonObjectBuilder result = Json.createObjectBuilder();
		result.add("status", "error");
		result.add("details", message);
		response.getOutputStream().println(result.build().toString());
	}
	
}
