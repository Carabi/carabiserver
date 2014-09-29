package ru.carabi.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.DatatypeConverter;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.FileOnServer;
import ru.carabi.server.kernel.FileStorage;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Управление передачей файла через поток. Этапы:<br>
 * 1 создание/чтение файла и объекта {@link FileOnServer} на текущем сервере или сокета на удалённом<br>
 * 2 выдача потока для перекачивания<br>
 * 3 При записи: выдача ID объекта FileOnServer: получение с удалённого сервера по сокету или обновление записи на текущем
 * @author sasha
 */
public class FileStreamer implements AutoCloseable {
	private static final Logger logger = CarabiLogging.getLogger(FileStreamer.class);
	
	String filename;
	private CarabiAppServer targetServer;
	private String token;
	private String contentLength;
	private Socket socket;
	private String getUrl, putUrl;
	private File file;
	private InputStream inputStream;
	private OutputStream outputStream;
	private FileOnServer fileMetadata;
	private FileStorage fileStorage;

	public void setGetUrl(String getUrl) {
		this.getUrl = getUrl;
	}

	public void setPutUrl(String putUrl) {
		this.putUrl = putUrl;
	}

	
	/**
	 * Проксирование
	 * @param filename пользовательское имя файла
	 * @param targetServer целевой сервер
	 * @param token токен для подключения
	 * @param contentLength длина потока (определяется сервлетом из заголовков для генерации заголовков в запросе)
	 */
	public FileStreamer(String filename, CarabiAppServer targetServer, String token, String contentLength) {
		this.filename = filename;
		this.targetServer = targetServer;
		this.token = token;
		this.contentLength = contentLength;
	}

	/**
	 * Запись на текущем сервере
	 * @param filename пользовательское имя файла
	 */
	public FileStreamer(String filename, FileStorage fileStorage) {
		this.filename = filename;
		this.fileStorage = fileStorage;
		this.fileMetadata = fileStorage.createFileMetadata(filename);
		this.file = new File(fileMetadata.getContentAddress());
	}

	/**
	 * Чтение на текущем сервере
	 * @param attachment 
	 */
	public FileStreamer(FileOnServer attachment) {
		this.fileMetadata = attachment;
		this.file = new File(attachment.getContentAddress());
	}

	public OutputStream getOutputStream() throws IOException {
		if (file != null) {
			outputStream = new FileOutputStream(file);
			return outputStream;
		} else if (targetServer != null) {
			socket = new Socket(targetServer.getComputer(), targetServer.getGlassfishPort());
			outputStream = socket.getOutputStream();
			PrintStream printStream = new PrintStream(outputStream);
			String head = "PUT /" + targetServer.getContextroot() + putUrl + " HTTP/1.1\n" +
					"Host: appl.cara.bi\n" +
					"Content-type:application/octet-stream\n" +
					"Connection: close\n" +
					"Token: " + token + "\n" +
					"Filename-Base64: " + DatatypeConverter.printBase64Binary(filename.getBytes("UTF-8")) + "\n" +
					"Content-Length: " + contentLength + "\n";
			printStream.print(head);
			printStream.println();
			return outputStream;
		} else {
			return null;
		}
	}

	public InputStream getInputStream() throws IOException {
		if (file != null) {
			inputStream = new FileInputStream(file);
			return inputStream;
		} else if (targetServer != null) {
			socket = new Socket(targetServer.getComputer(), targetServer.getGlassfishPort());
			outputStream = socket.getOutputStream();
			PrintStream printStream = new PrintStream(outputStream);
			String head = "GET /" + targetServer.getContextroot() + getUrl + " HTTP/1.1\n" +
					"Host: appl.cara.bi\n" +
					"Connection: close\n";
			printStream.print(head);
			printStream.println();
			inputStream = socket.getInputStream();
			List<String> httpHeaders = Utls.skipHttpHeaders(inputStream);
			String status = httpHeaders.get(0);
			if (!"HTTP/1.1 200 OK".equals(status)) {
				logger.log(Level.WARNING, "bad responde in proxy, {0}", status);
			}
			return inputStream;
		} else {
			return null;
		}
	}

	public Long getFileId() throws IOException {
		if (file != null) {
			String mimeType = Files.probeContentType(file.toPath());
			fileMetadata.setMimeType(mimeType);
			fileMetadata.setContentLength(file.length());
			fileMetadata = fileStorage.updateFileMetadata(fileMetadata);
			return fileMetadata.getId();
		} else if (socket != null) {
			boolean ok = true;
			InputStream responseStream = socket.getInputStream();
			List<String> httpHeaders = Utls.skipHttpHeaders(responseStream);
			String status = httpHeaders.get(0);
			ok = "HTTP/1.1 200 OK".equals(status);
			final BufferedReader resultReader = new BufferedReader(new InputStreamReader(responseStream));
			String result = resultReader.readLine();
			if (ok) {
				try {
					return Long.parseLong(result);
				} catch (NumberFormatException e) {
					logger.log(Level.SEVERE, "Bad response from {0} in proxyAttachment, error: {1}", new String[]{targetServer.getComputer(), result});
				}
			} else {
				logger.log(Level.SEVERE, "Bad response from {0} in proxyAttachment, error: {1} {2}", new String[]{targetServer.getComputer(), status, result});
			}
		}
		return null;
	}
	
	/**
	 * Создание объекта FileStreamer
	 * @return 
	 */
	public static FileStreamer makeFileStreamer(CarabiAppServer targetServer, String filename, FileOnServer fileMetadata, String token, HttpServletRequest request, String putUrl, FileStorage fileStorage) {
		CarabiAppServer currentServer = Settings.getCurrentServer();
		if (currentServer.equals(targetServer)) {
			if (!StringUtils.isEmpty(filename)) {//запись
				return new FileStreamer(filename, fileStorage);
			} else if (fileMetadata != null) {//чтение
				return new FileStreamer(fileMetadata);
			}
		} else { //проксирование
			FileStreamer fileStreamer = new FileStreamer(filename, targetServer, token, request.getHeader("Content-Length"));
			fileStreamer.setPutUrl(putUrl);
			return fileStreamer;
		}
		return null;
	}
	
	@Override
	public void close() throws IOException {
		if (outputStream != null) {
			outputStream.flush();
			outputStream.close();
			outputStream = null;
		}
		if (inputStream != null) {
			inputStream.close();
		}
		if (socket != null) {
			socket.close();
			socket = null;
		}
		file = null;
		targetServer = null;
	}

}
