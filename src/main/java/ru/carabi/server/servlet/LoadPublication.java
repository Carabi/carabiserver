package ru.carabi.server.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
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
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.RegisterException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.entities.Publication;
import ru.carabi.server.face.injectable.CurrentClient;
import ru.carabi.server.kernel.ProductionBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 * Загрузка и выгрузка публикаций
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebServlet(name = "LoadPublication", urlPatterns = {"/LoadPublication"})
public class LoadPublication extends HttpServlet {
	
	@Inject private CurrentClient currentClient;
	@EJB private UsersControllerBean usersController;
	@EJB private ProductionBean productionBean;
	
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

}
