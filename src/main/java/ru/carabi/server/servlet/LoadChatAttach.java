package ru.carabi.server.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.mail.internet.MimeUtility;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import javax.xml.ws.Holder;
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
import ru.carabi.server.entities.ChatMessage;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.ChatBean;
import ru.carabi.server.kernel.FileStorage;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.rest.RestException;

/**
 * Загрузка и выгрузка вложенных файлов в чат.
 * Для получения вложения необходимо сделать GET-запрос на URL
 * <code>{адрес сервера}/load_chat_attach?token={токен}&id={ID сообщения с вложением}</code>.
 * <br/>
 * <br/>
 * Для отправки сообщения с вложением необходимо сделать POST-запрос на URL
 * <code>{адрес сервера}/load_chat_attach?token_sender={токен отправителя}&login_receiver={логин получателя}</code>.
 * Имя файла можно передать в параметре filename или заголовке Filename-Base64.
 * В теле POST-запроса передать загружаемый файл.
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebServlet(name = "LoadChatAttach", urlPatterns = {"/load_chat_attach"}, asyncSupported = true)
public class LoadChatAttach extends HttpServlet {
	private static final Logger logger = CarabiLogging.getLogger(ru.carabi.server.servlet.LoadChatAttach.class);
	private static final String urlPattern = "/load_chat_attach";

	@EJB private UsersControllerBean uc;
	@EJB private ChatBean chatBean;
	@EJB private AdminBean admin;

	/**
	 * Обработка метода GET. Возвращает вложение из чата.
	 * На вход требуются параметры "token" и "id" -- id  сообщения (объекта {@link ChatMessage}),
	 * содержащего вложение. Вывод содержит заголовки Content-Disposition (с именем
	 * файла в RFC 2047 для браузеров) и Filename-Base64
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String token = request.getParameter("token");
		if (StringUtils.isEmpty(token)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter token required");
			return;
		}
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			Long id = Long.valueOf(request.getParameter("id"));
			FileOnServer file = chatBean.getMessageAttachement(logon, id);
			response.setHeader("Content-Disposition", "attachment; filename=\"" + MimeUtility.encodeText(file.getName()) +"\"");
			response.setHeader("Filename-Base64", DatatypeConverter.printBase64Binary(file.getName().getBytes("UTF-8")));
			response.setHeader("Content-Type", file.getMimeType());
			response.setHeader("Content-Length", "" +file.getContentLength());
			try (FileStreamer fileStreamer = FileStreamer.makeFileStreamer(logon.getUser().getMainServer(), null, file, token, request, urlPattern, wrapFileStorage())) {
				fileStreamer.setGetUrl(urlPattern + "?token=" + token + "&id=" + id);
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
	
	/**
	 * Обработка метода PUT. Для проксирования между серверами. Записывает входящий поток данных в
	 * файл, создаёт объект {@link FileOnServer}.
	 * Токен и имя файла передаются в заголовках ("Token" и "Filename-Base64").
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
		if (contentLength > Settings.maxAttachmentSize) {
			sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Max attachment size is: " + Settings.maxAttachmentSize + ", your is: " + contentLength);
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
				return chatBean.createAttachment(userFilename);
			}
			@Override
			public FileOnServer updateFileMetadata(FileOnServer fileMetadata) {
				return chatBean.updateAttachment(fileMetadata);
			}
		};
	}

	/**
	 * Обработка метода POST. Отправляет сообщение с файлом. Передаёт входящий поток данных
	 * на сервера отправителя и получателя через doPut (если это не текущий сервер), создаёт
	 * для них {@link FileOnServer}, отправляет его в {@link ChatMessage} с
	 * использованием {@link ChatBean#forwardMessage(ru.carabi.server.entities.CarabiUser, ru.carabi.server.entities.CarabiUser, java.lang.String)}
	 * В параметрах необходимо передать токен отправителя("token_sender") и логин получателя("login_receiver").<br/>
	 * Имя файла можно передать в параметре "filename", используя urlencode или"
	 * в заголовке "Filename-Base64".<br/>
	 * Логический параметр "save_sent" &mdash; сохранять файл у отправителя. При передаче
	 * любой непустой строки, кроме "0" или "false" файл записывается не только получателю, но и
	 * отправителю. (Если их обслужвает один сервер, то дубликат в ФС не создаётся.)
	 * По умолчанию false. <br/>
	 * Необязательный параметр: комментарий ("comment"), становится текстом
	 * сообщения (по умолчанию используется имя файла).<br/>
	 * Возвращает id отправленного сообщения
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		//валидация параметров и заголовков
		String token = request.getParameter("token_sender");
		if (StringUtils.isEmpty(token)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter token_sender required");
			return;
		}
		int contentLength = request.getContentLength();
		if (contentLength > Settings.maxAttachmentSize) {
			sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Max attachment size is: " + Settings.maxAttachmentSize + ", your is: " + contentLength);
			return;
		}
		String loginReceiver = request.getParameter("login_receiver");
		if (StringUtils.isEmpty(loginReceiver)) {
			sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter login_receiver required");
			return;
		}
		String filenameInput = request.getParameter("filename");
		String filenameUser;
		if (StringUtils.isEmpty(filenameInput)) {
			filenameInput = request.getHeader("Filename-Base64");
			if (filenameInput == null) {
				sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter filename or header Filename-Base64 required");
				return;
			} else {
				filenameUser = new String(DatatypeConverter.parseBase64Binary(filenameInput), "UTF-8");
			}
		} else {
			filenameUser = Utls.decodeStringAsByteArray(filenameInput);
		}
		String saveSentStr = request.getParameter("save_sent");
		boolean saveSent = (!StringUtils.isEmpty(saveSentStr) && !"0".equals(saveSentStr) && !"false".equalsIgnoreCase(saveSentStr));
		String comment = request.getParameter("comment");
		if (StringUtils.isEmpty(comment)) {
			comment = filenameUser;
		}
		try (UserLogon logonSender = uc.tokenAuthorize(token, false)) {
			
			//Определяем сервера
			CarabiUser receiver = admin.findUser(loginReceiver);
			CarabiUser sender = logonSender.getUser();
			CarabiAppServer receiverServer = receiver.getMainServer();
			CarabiAppServer senderServer = sender.getMainServer();
			//Отправляем файл по назначению
			Holder<Long> receiverAttachmentId = new Holder<>();
			Holder<Long> senderAttachmentId = new Holder<>();
			handleAttachment(receiverServer, senderServer, saveSent, filenameUser, request, CarabiFunc.encrypt(token), receiverAttachmentId, senderAttachmentId);
			//Отправляем уведомления
			Long id = chatBean.sendMessage(sender, receiver, comment, senderAttachmentId.value, receiverAttachmentId.value);
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("id", id);
			response.getWriter().print(result.build().toString());
		} catch (RegisterException e) {
			sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (CarabiException ex) {
			logger.log(Level.INFO, "", ex);
			Throwable cause = ex.getCause();
			Logger.getLogger(LoadChatAttach.class.getName()).log(Level.SEVERE, null, ex);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
		} catch (GeneralSecurityException ex) {
			Logger.getLogger(LoadChatAttach.class.getName()).log(Level.SEVERE, null, ex);
			sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
		}
	}
	
	/**
	 * Конечная обработка запроса с вложением. Для сервера отправителя и получателя: сохраняет вложение на текущем сервере
	 * (если он -- целевой) или проксирует на целевой.
	 * @param receiverServer
	 * @param senderServer
	 * @param filename
	 * @param request
	 * @param token
	 * @return
	 * @throws IOException 
	 */
	private Long handleAttachment(
			CarabiAppServer receiverServer,
			CarabiAppServer senderServer,
			boolean saveSent,
			String filename,
			HttpServletRequest request,
			String token,
			Holder<Long> receiverAttachmentId,
			Holder<Long> senderAttachmentId
	) throws IOException {
		//подготовка потока для получателя
		try (FileStreamer receiverAttachmentStreamer = FileStreamer.makeFileStreamer(receiverServer, filename, null, token, request, urlPattern, wrapFileStorage())) {
			FileStreamer senderAttachmentStreamer = null;
			//подготовка потока для отправителя, если надо и сервера не совпадают
			if (saveSent && !senderServer.equals(receiverServer)) {
				senderAttachmentStreamer = FileStreamer.makeFileStreamer(senderServer, filename, null, token, request, urlPattern, wrapFileStorage());
			}
			//Перекачивание
			if (senderAttachmentStreamer != null) {
				Utls.proxyStreams(request.getInputStream(), receiverAttachmentStreamer.getOutputStream(), senderAttachmentStreamer.getOutputStream());
			} else {
				Utls.proxyStreams(request.getInputStream(), receiverAttachmentStreamer.getOutputStream());
			}
			//Получение ID
			receiverAttachmentId.value = receiverAttachmentStreamer.getFileId();
			if (senderAttachmentStreamer != null) {
				senderAttachmentId.value = senderAttachmentStreamer.getFileId();
				senderAttachmentStreamer.close();
			} else if (saveSent) {
				assert senderServer.equals(receiverServer);
				senderAttachmentId.value = receiverAttachmentId.value;
			}
		}//receiverOutputStreamer.close()
		return null;
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
	
}
