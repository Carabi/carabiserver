package ru.carabi.server.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.mail.internet.MimeUtility;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
/**
 * Загрузка и выгрузка вложенных файлов в Carabi.
 * Для получения файла необходимо сделать GET-запрос на URL
 * <code>{адрес сервера}/load_carabi_attach?token={токен}&id={ID вложения}</code>.
 * <br/>
 * <br/>
 * Для записи файла в базу можно передать файл по HTTP-потоку в чистом виде
 * (проще при разработке клиента с нуля) или использовать формат multipart/form-data
 * (совместимо с отправкой файлов из браузера) <br/>
 * В первом случае необходимо сделать POST-запрос на URL
 * <pre>{адрес сервера}/load_carabi_attach?token={токен}&parentid={ID родительской карточки}&filename={пользовательское имя файла}</pre>,
 * задать заголовок Content-Length, в теле POST-запроса передать загружаемый файл.
 * <br/>
 * Во втором случае пользовательское имя файла передаётся рядом с его содержимым.
 * Параметры token и parentid по-прежнему следует передавать через URL (как POST-параметры пока не обрабатываются).
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebServlet(name = "LoadCarabiAttach", urlPatterns = {"/load_carabi_attach"}, asyncSupported = true)
public class LoadCarabiAttach extends HttpServlet {
	private static final Logger logger = CarabiLogging.getLogger(ru.carabi.server.servlet.LoadCarabiAttach.class);

	@EJB private UsersControllerBean uc;

	/**
	 * Обработка метода <code>GET</code> &mdash; получение файла из базы.
	 *
	 * @param request servlet request
	 * @param response servlet response
	 * @throws ServletException if a servlet-specific error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		String token = request.getParameter("token");
		String sql = "SELECT COMPRESSOR.BLOB_DECOMPRESS(T.DOC_CONTENT) AS CONTENT, T.DOC_NAME AS FILENAME,\n" +
						"T.DOC_CONTENT_TYPE AS CONTENT_TYPE FROM DOCUMENTS_TREE T\n" +
						"WHERE T.DOCUMENT_ID = ?";
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			Long id = Long.valueOf(request.getParameter("id"));
			Connection connection = logon.getConnection();
			PreparedStatement statement = connection.prepareStatement(sql);
			statement.setLong(1, id);
			ResultSet resultSet = statement.executeQuery();
			if (resultSet.next()) {
				Blob blob = resultSet.getBlob("CONTENT");
				final String filename = MimeUtility.encodeText(resultSet.getString("FILENAME"));
				response.setHeader("Content-Disposition", "attachment; filename=\"" + filename +"\"");
				response.setHeader("Content-length", String.valueOf(blob.length()));
				response.setHeader("Content-type", "application/octet-stream");
				int bs = 1024 * 1024;
				OutputStream out;
				try (InputStream blobStream = blob.getBinaryStream()) {
					byte[] buffer = new byte[bs];
					int bytesRead;
					out = response.getOutputStream();
					while ((bytesRead = blobStream.read(buffer)) > 0) {
						out.write(buffer, 0, bytesRead);
					}
				}
				blob.free();
				out.flush();
				out.close();
			} else {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			}
			logon.freeConnection(connection);
		} catch (NumberFormatException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "illegal ID input");
		} catch (SQLException e) {
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Oracle error");
		} catch (CarabiException e) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		}
	}

	/**
	 * Обработка метода <code>POST</code> &mdash; запись файла в базу.
	 *
	 * @param request servlet request
	 * @param response servlet response
	 * @throws ServletException if a servlet-specific error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		InputStream inputStream = request.getInputStream();
		String token = request.getParameter("token");
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			boolean isMultipartContent = ServletFileUpload.isMultipartContent(request);
			int parentid = Integer.valueOf(request.getParameter("parentid"));
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
						uploadAttachment(logon, fileItem.getInputStream(), response, fileItem.getName(), parentid);
					}
				}
			} else {
				String clientFilename = request.getParameter("filename");
				uploadAttachment(logon, inputStream, response, clientFilename, parentid);
			}
		} catch (CarabiException ex) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (FileUploadException | SQLException ex) {
			Logger.getLogger(LoadCarabiAttach.class.getName()).log(Level.SEVERE, null, ex);
		}
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
	
	/**
	 * Размещение вложения в БД.
	 * @param logon пользовательская сессия
	 * @param inputStream входящий HTTP-поток
	 * @param response
	 * @param filename
	 * @param parentId
	 * @throws IOException
	 * @throws CarabiException
	 * @throws SQLException 
	 */
	private void uploadAttachment(UserLogon logon, InputStream inputStream, HttpServletResponse response, String filename, long parentId) throws IOException, CarabiException, SQLException {
		Connection connection = logon.getConnection();
		String sql = "begin ? := PKG_ATTACHMENT.ADD_ATTACHMENT(?, " + //:DOCUMENT_ID$ INTEGER
				"?, " + // :FILE$ BLOB
				"?" + // :FILE_NAME$ VARCHAR
				"); end;";
		try (CallableStatement statement = connection.prepareCall(sql)) {
			Blob blob = connection.createBlob();
			try (OutputStream blobStream = blob.setBinaryStream(1)) {
				Utls.proxyStreams(inputStream, blobStream);
			}
			statement.registerOutParameter(1, Types.NUMERIC);
			statement.setLong(2, parentId);
			statement.setBlob(3, blob);
			statement.setString(4, filename);
			statement.execute();
			blob.free();
			response.getWriter().print(statement.getLong(1));
		}
		logon.freeConnection(connection);
	}
	
	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String token = request.getParameter("token");
		String sql = "begin PKG_ATTACHMENT.DELETE_ATTACHMENT(?, " + //:DOCUMENT_ID$ INTEGER
				"?" + // :FILE_NAME$ VARCHAR
				"); end;";
		try (UserLogon logon = uc.tokenAuthorize(token)) {
			Long parentid = Long.valueOf(request.getParameter("parentid"));
			String filename = request.getParameter("filename");
			Connection connection = logon.getConnection();
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, parentid);
				statement.setString(2, filename);
				statement.execute();
			}
			logon.freeConnection(connection);
		} catch (CarabiException ex) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unknown token");
		} catch (SQLException ex) {
			Logger.getLogger(LoadCarabiAttach.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}
}
