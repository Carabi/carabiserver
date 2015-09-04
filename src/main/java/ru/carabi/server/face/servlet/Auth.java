package ru.carabi.server.face.servlet;

import java.io.IOException;
import java.util.Map;
import java.util.ResourceBundle;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.face.injectable.CurrentClient;
import ru.carabi.server.kernel.GuestBean;
import ru.carabi.server.kernel.UsersPercistenceBean;

/**
 * Авторизация для JSF
 * @author sasha
 */
@WebServlet(name = "Auth", urlPatterns = {"/Auth"})
public class Auth extends HttpServlet {
	private ResourceBundle l10n = ResourceBundle.getBundle("ru.carabi.server.face.Locale");
	@EJB private GuestBean guest;
	@EJB private UsersPercistenceBean usersPercistence;
	
	@Inject private  CurrentClient currentClient;
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.sendRedirect("index.xhtml");
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
		Map<String, String[]> parameterMap = request.getParameterMap();
		if (!parameterMap.containsKey("login") || !parameterMap.containsKey("password")) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		ServletOutputStream outputStream = response.getOutputStream();
		final String login = parameterMap.get("login")[0];
		final String password = parameterMap.get("password")[0];
		String passwordHash = DigestUtils.md5Hex(login.toUpperCase() + password);
		if (!usersPercistence.userExists(login)) {
			currentClient.getProperties().setProperty("authMessage", l10n.getString("badLoginOrPassword"));
			currentClient.setTest("Does it work?");
			response.sendRedirect("auth.xhtml");
			return;
		}
		try {
			CarabiUser user = usersPercistence.findUser(login);
			guest.registerUserLight(user, passwordHash, login, true, true, null, null, null);
		} catch (CarabiException e) {
			//response.
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

}
