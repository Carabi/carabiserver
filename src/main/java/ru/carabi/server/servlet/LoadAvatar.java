package ru.carabi.server.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.servlet.ServletException;
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
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.FileUploadException;

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
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			String login = request.getParameter("login");
			if (StringUtils.isEmpty(login)) {
				login = logon.getUser().getLogin();
			}
			CarabiUser findUser = null;
			try {
				findUser = uc.findUser(login);
			} catch (CarabiException e) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "user not found");
				return;
			}
			FileOnServer file = findUser.getAvatar();
			if (file == null) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "no avatar for that user");
				return;
			}
			logger.log(Level.INFO, "getScaledAvatar {0}x{1}", new Object[]{width, height});
			file = getScaledAvatar(logon, file, width, height);
			//etag -- идентификатор совпадения контента
			String etag = "avatar_" + file.getId();
			if (width > 0 || height > 0) {
				if (width > 0) {
					etag += ("_w" + width);
				}
				if (height > 0) {
					etag += ("_h" + height);
				}
				etag += ("_thumb" + file.getId());
			}
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
			try (FileStreamer fileStreamer = FileStreamer.makeFileStreamer(Settings.getMasterServer(), null, file, token, request, urlPattern, null)) {
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
			if (cause != null && RestException.class.isAssignableFrom(cause.getClass())) {
				RestException restException = (RestException) cause;
				sendError(response, restException.getResponse().getStatus(), cause.getMessage());
			} else if (cause != null) {
				sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, cause.getMessage());
			} else {
				sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
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
		int contentLength = request.getContentLength();
		if (contentLength > Settings.maxAvatarSize) {
			sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Max avatar size is: " + Settings.maxAvatarSize + ", your is: " + contentLength);
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
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			FileStreamer streamer = new FileStreamer(filenameUser, wrapFileStorage(logon, uc.findUser(filenameUser)));
			InputStream inputStream;
			boolean isMultipartContent = ServletFileUpload.isMultipartContent(request);
			if (isMultipartContent) {
				FileItemFactory factory = new DiskFileItemFactory();
				ServletFileUpload upload = new ServletFileUpload(factory);
				List<FileItem> fields = upload.parseRequest(request);
				Iterator<FileItem> it = fields.iterator();
				if (!it.hasNext()) {
					//No fields found;
					return;
				}
				while (it.hasNext()) {
					FileItem fileItem = it.next();
					boolean isFormField = fileItem.isFormField();
					if (!isFormField) {
						inputStream = fileItem.getInputStream();
						uploadAvatar(inputStream, streamer, response);
						return;
					}
				}
			} else {
				inputStream = request.getInputStream();
				uploadAvatar(inputStream, streamer, response);
			}
		} catch (RegisterException e) {
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (CarabiException | FileUploadException e) {
			logger.log(Level.SEVERE, null, e);
		}
	}
	
	private void uploadAvatar(InputStream inputStream, FileStreamer streamer, HttpServletResponse response) throws IOException {
		Utls.proxyStreams(inputStream, streamer.getOutputStream());
		Long fileId = streamer.getFileId();
		response.getOutputStream().println(fileId);
	}
	
	private FileStorage wrapFileStorage(final UserLogon logon, final CarabiUser targetUser) {
		return new FileStorage() {
			@Override
			public FileOnServer createFileMetadata(String userFilename) {
				try {
					return admin.createUserAvatar(logon, targetUser);
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
	 * на центральный сервер (если это не он), создаёт
	 * для него {@link FileOnServer}, привязывает к {@link CarabiUser}.
	 * В параметрах необходимо передать токен клиента или администратора("token") и,
	 * если пользователь не редактирует сам себя, логин редактируемого пользователя
	 * ("login" -- в этом случае токен должен принадлежать зарегистрированному приложению).<br/>
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		//валидация параметров и заголовков
		String login = null, token = null;
		InputStream inputStream = null;
		int contentLength = request.getContentLength();
		if (contentLength > Settings.maxAvatarSize) {
			sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Max avatar size is: " + Settings.maxAvatarSize + ", your is: " + contentLength);
			return;
		}
		boolean isMultipartContent = ServletFileUpload.isMultipartContent(request);
		if (isMultipartContent) {
			FileItemFactory factory = new DiskFileItemFactory();
			ServletFileUpload upload = new ServletFileUpload(factory);
			List<FileItem> fields;
			try {
				fields = upload.parseRequest(request);
				Iterator<FileItem> it = fields.iterator();
				if (!it.hasNext()) {
					sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Empty request");
					return;
				}
				int i = 0;
				while (it.hasNext()) {
					FileItem fileItem = it.next();
					boolean isFormField = fileItem.isFormField();
					if (!isFormField) {
						inputStream = fileItem.getInputStream();
					}
					if (isFormField && "token".equals(fileItem.getFieldName())) {
						token = fileItem.getString();
					}
					if (isFormField && "login".equals(fileItem.getFieldName())) {
						login = fileItem.getString();
					}
				}
				if (inputStream == null) {
					sendError(response, HttpServletResponse.SC_BAD_REQUEST, "No input file");
					return;
				}
			} catch (FileUploadException ex) {
				Logger.getLogger(LoadAvatar.class.getName()).log(Level.SEVERE, null, ex);
			}
		} else {
			token = request.getParameter("token");
			login = request.getParameter("login");
			inputStream = request.getInputStream();
		}
		
		if (StringUtils.isEmpty(token)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter token required");
			return;
		}
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			if (login == null) {
				login = logon.userLogin();
			}
			
			//Определяем владельца аватара
			CarabiUser user;
			if (logon.isPermanent()) {
				if (StringUtils.isEmpty(login)) {
					sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter login required");
					return;
				}
				user = uc.findUser(login);
			} else {
				user = logon.getUser();
			}
			if (user == null) {
				sendError(response, HttpServletResponse.SC_NOT_FOUND, "User not found");
				return;
			}
			//Отправляем файл по назначению
			CarabiAppServer targetServer = Settings.getMasterServer();
			Long avatarId = handleAvatar(logon, targetServer, user, request, inputStream, CarabiFunc.encrypt(token));
			response.getWriter().print(0);
		} catch (RegisterException e) {
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (CarabiException ex) {
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
			UserLogon logon,
			CarabiAppServer targetServer,
			CarabiUser targetUser,
			HttpServletRequest request,
			InputStream inputStream,
			String token
	) throws IOException {
		//подготовка потока
		FileStorage wrapedFileStorage = wrapFileStorage(logon, targetUser);
		try (FileStreamer avatarStreamer = FileStreamer.makeFileStreamer(targetServer, targetUser.getLogin(), null, token, request, urlPattern, wrapedFileStorage)) {
			//Перекачивание
			Utls.proxyStreams(inputStream, avatarStreamer.getOutputStream());
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
