package ru.carabi.server.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.mail.internet.MimeUtility;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.libs.CarabiFunc;
import ru.carabi.server.CarabiException;
import ru.carabi.server.FileStreamer;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.FileStorage;
import ru.carabi.server.kernel.ImagesBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.rest.RestException;

/**
 * Загрузка и выгрузка аватаров.
 * Для получения аватара необходимо сделать GET-запрос на URL
 * <code>{адрес сервера}/load_avatar?token={токен}&login={логин пользователя}</code>.
 * <br/>
 * Если логин не задан -- пользователь получает свой аватар.
 * <br/>
 * Для изменения аватара необходимо сделать POST-запрос на URL
 * <code>{адрес сервера}/load_avatar?token={токен пользователя или администратора}&login={логин пользователя (только при редактировании администратором)}</code>.
 * В теле POST-запроса передать загружаемый файл.
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebServlet(name = "LoadAvatar", urlPatterns = {"/load_avatar"}, asyncSupported = true)
public class LoadAvatar extends HttpServlet {
	private static final Logger logger = CarabiLogging.getLogger(ru.carabi.server.servlet.LoadAvatar.class);
	private static final String urlPattern = "/load_avatar";

	@EJB private UsersControllerBean uc;
	@EJB private AdminBean admin;
	@EJB private ImagesBean imagesBean;

	/**
	 * Обработка метода GET. Возвращает аватар.
	 * На вход требуются параметры "token" и "login" -- логин пользователя, аватар которого требуется.
	 * Если login не задан -- возвращается аватар текущего пользователя.
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Map<String, String[]> parameterMap = request.getParameterMap();
		for (String param: parameterMap.keySet()) {
			logger.log(Level.INFO, "{0}={1}", new Object[]{param, parameterMap.get(param)[0]});
		}
		String token = request.getParameter("token");
		if (StringUtils.isEmpty(token)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter token required");
			return;
		}
		//ширина масштабирования (по умолчанию без масштабирования)
		int width = parceIntParam(request, response, -1, "width", "w");
		//высота масштабирования (по умолчанию без масштабирования)
		int height = parceIntParam(request, response, -1, "height", "h");
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			String login = request.getParameter("login");
			if (StringUtils.isEmpty(login)) {
				login = logon.getUser().getLogin();
			}
			CarabiUser findUser = null;
			try {
				findUser = admin.findUser(login);
			} catch (CarabiException e) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "user not found");
				return;
			}
			FileOnServer file = findUser.getAvatar();
			if (file == null) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "no avatar for that user");
				return;
			}
			//etag -- идентификатор совпадения контента
			String etag = "avatar_" + file.getId();
			if (width > 0) {
				etag += ("_w" + width);
			}
			if (height > 0) {
				etag += ("_h" + height);
			}
			logger.log(Level.INFO, "getScaledAvatar {0}x{1}", new Object[]{width, height});
			file = getScaledAvatar(logon, file, width, height);
			response.setHeader("Filename-Base64", DatatypeConverter.printBase64Binary(file.getName().getBytes("UTF-8")));
			response.setHeader("Content-Type", file.getMimeType());
			response.setHeader("ETag", etag);
			//при совпадении с содержимым в кеше клиента выходим
			if (etag.equals(request.getHeader("If-None-Match"))) {
				response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
				return;
			}
			response.setHeader("Content-Length", "" +file.getContentLength());
			//передача клиенту (чтение или проксирование)
			try (FileStreamer fileStreamer = FileStreamer.makeFileStreamer(Settings.getMasterServer(), null, file, token, request, urlPattern, wrapFileStorage())) {
				String getUrl = urlPattern + "?token=" + token + "&login=" + login;
				if (width >= 0) {
					getUrl += ("&width=" + width);
				}
				if (height >= 0) {
					getUrl += ("&height=" + height);
				}
				fileStreamer.setGetUrl(getUrl);
				InputStream inputStream = fileStreamer.getInputStream();
				try (OutputStream outputStream = response.getOutputStream()) {
					Utls.proxyStreams(inputStream, outputStream);
					outputStream.flush();
				}
			}
		} catch (NumberFormatException e) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "illegal ID input");
		} catch (RegisterException e) {
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (CarabiException e) {
			Throwable cause = e.getCause();
			if (RestException.class.isAssignableFrom(cause.getClass())) {
				RestException restException = (RestException) cause;
				sendError(response, restException.getResponse().getStatus(), cause.getMessage());
			}
		}
	}

	private int parceIntParam(HttpServletRequest request, HttpServletResponse response, int defaultValue, String... paramName) throws IOException {
		int value = defaultValue;
		String valueStr = request.getParameter(paramName[0]);
		int i = 1;
		while (StringUtils.isEmpty(valueStr) && i<paramName.length) {
			valueStr = request.getParameter(paramName[i]);
			i++;
		}
		if (!StringUtils.isEmpty(valueStr)) {
			try {
				value = Integer.valueOf(valueStr);
			} catch (NumberFormatException e) {
				sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter " + paramName[0] + " invalid");
			}
		}
		return value;
	}
	
	/**
	 * Обработка метода PUT. Для проксирования между серверами. Записывает входящий поток данных в
	 * файл, создаёт объект {@link FileOnServer}.
	 * Токен и логин пользователя (имя файла) передаются в заголовках ("Token" и "Filename-Base64").
	 */
	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String token = request.getHeader("Token");
		String filenameInput = request.getHeader("Filename-Base64");
		if (StringUtils.isEmpty(token) || StringUtils.isEmpty(filenameInput)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Not enough parameters. Required: token, filename");
			return;
		}
		try {
			token = CarabiFunc.decrypt(token);
		} catch (GeneralSecurityException ex) {
			logger.log(Level.INFO, "Token " + token + " incorrect", ex);
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Token " + token + " incorrect");
			return;
		}
		String filenameUser = new String(DatatypeConverter.parseBase64Binary(filenameInput), "UTF-8");
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			FileStreamer streamer = new FileStreamer(filenameUser, wrapFileStorage());
			ServletInputStream inputStream = request.getInputStream();
			Utls.proxyStreams(inputStream, streamer.getOutputStream());
			Long fileId = streamer.getFileId();
			response.getOutputStream().println(fileId);
		} catch (RegisterException e) {
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (CarabiException e) {
			logger.log(Level.SEVERE, null, e);
		}
	}

	private FileStorage wrapFileStorage() {
		return new FileStorage() {
			@Override
			public FileOnServer createFileMetadata(String userFilename) {
				try {
					return admin.createUserAvatar(userFilename);
				} catch (CarabiException ex) {
					logger.log(Level.SEVERE, null, ex);
				}
				return null;
			}
			@Override
			public FileOnServer updateFileMetadata(FileOnServer fileMetadata) {
				return admin.refreshAvatar(fileMetadata);
			}
		};
	}
	
	/**
	 * Обработка метода POST. Создаёт или обновляет аватар пользователя. Передаёт входящий поток данных
	 * на центральный сервера (если это не он), создаёт
	 * для него {@link FileOnServer}, привязывает к {@link CarabiUser}.
	 * В параметрах необходимо передать токен клиента или администратора("token") и,
	 * если пользователь не редактирует сам себя, логин редактируемого пользователя
	 * ("login" -- в этом случае токен должен принадлежать зарегистрированному приложению).<br/>
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		//валидация параметров и заголовков
		String token = request.getParameter("token");
		if (StringUtils.isEmpty(token)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter token required");
			return;
		}
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			
			//Определяем владельца аватара
			CarabiUser user;
			if (logon.isPermanent()) {
				String login = request.getParameter("login");
				if (StringUtils.isEmpty(login)) {
					sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter login required");
					return;
				}
				user = admin.findUser(login);
			} else {
				user = logon.getUser();
			}
			if (user == null) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "User not found");
				return;
			}
			//Отправляем файл по назначению
			CarabiAppServer targetServer = Settings.getMasterServer();
			Long avatarId = handleAvatar(targetServer, user.getLogin(), request, CarabiFunc.encrypt(token));
			response.getWriter().print(0);
		} catch (RegisterException e) {
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (CarabiException ex) {
			Throwable cause = ex.getCause();
			Logger.getLogger(LoadAvatar.class.getName()).log(Level.SEVERE, null, ex);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
		} catch (GeneralSecurityException ex) {
			Logger.getLogger(LoadAvatar.class.getName()).log(Level.SEVERE, null, ex);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
		}
	}
	
	/**
	 * Конечная обработка запроса с аватаром. Сохраняет файл на текущем сервере
	 * (если он -- целевой) или проксирует на целевой.
	 * @param receiverServer
	 * @param senderServer
	 * @param filename
	 * @param request
	 * @param token
	 * @return
	 * @throws IOException 
	 */
	private Long handleAvatar(
			CarabiAppServer targetServer,
			String filename,
			HttpServletRequest request,
			String token
	) throws IOException {
		//подготовка потока
		try (FileStreamer avatarStreamer = FileStreamer.makeFileStreamer(targetServer, filename, null, token, request, urlPattern, wrapFileStorage())) {
			//Перекачивание
			Utls.proxyStreams(request.getInputStream(), avatarStreamer.getOutputStream());
			//Получение ID
			return avatarStreamer.getFileId();
		}//avatarStreamer.close()
	}
	
	/**
	 * Returns a short description of the servlet.
	 *
	 * @return a String containing servlet description
	 */
	@Override
	public String getServletInfo() {
		return "Download and upload Carabi attaches";
	}
	
	private void sendError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.getOutputStream().println(message);
	}
	
	/**
	 * Изменение данных об аватаре с учётом требуемых размеров.
	 * На главном сервере -- собственно масштабирование (или получение данных
	 * из кеша), на других -- получение метаданных с главного сервера.
	 * @param file данные об оригинальном аватаре
	 * @param width требуемая ширина
	 * @param height требуемая высота
	 * @return данные об масштабированном аватаре
	 */
	private FileOnServer getScaledAvatar(UserLogon logon, FileOnServer file, int width, int height) throws CarabiException {
		if (width <= 0 && height <= 0) {
			return file;
		}
		return imagesBean.getThumbnail(logon, Settings.getMasterServer(), file, width, height, true);
	}
	
}
