package ru.carabi.server.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.xml.ws.Holder;
import ru.carabi.libs.CarabiEventType;
import ru.carabi.libs.CarabiFunc;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiAppServer;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.logging.CarabiLogging;
import ru.carabi.server.soap.GuestService;

/**
 * Служебный сервис для работы TCP Eventer и его клиентов.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Stateless
public class EventerBean {
	private static final Logger logger = CarabiLogging.getLogger(EventerBean.class);
	
	@EJB private UsersControllerBean usersController;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager em;
	
	/**
	 * Получить токен для авторизации в эвентере.
	 * @param token токен текущей сессии в SOAP, полученный в {@link GuestService}
	 * @return токен параллельной сессии для Eventer
	 */
	public String getEventerToken(String token) {
		try {
			UserLogon user = usersController.getUserLogon(token);
			if (user == null) {
				return "error: unknown token: " + token;
			}
			return CarabiFunc.encrypt(token);
		} catch (Exception ex) {
			logger.log(Level.SEVERE, "Error on encrypting token", ex);
			return "error: " + ex.getMessage();
		}
	}
	
	/**
	 * Отправить событие клиенту через Eventer.
	 * @param schema БД, из которой идёт событие
	 * @param login логин клиента
	 * @param eventcode код события
	 * @param message текст события
	 * @throws IOException 
	 * @throws CarabiException Если адресат не найден
	 */
	public void fireEvent(
			String schema,
			String login,
			short eventcode,
			String message) throws IOException, CarabiException {
		logger.log(Level.FINE, "fireEvent, parameters: {0}, {1}, {2}, {3}", new Object[] {schema, login, eventcode, message});
		final byte[] eventPackage = prepareFireEventPackage(schema, login, eventcode, message);
		final List<CarabiAppServer> servers = getTargetServers(login);
		logger.log(Level.FINE, "target servers: {0}", servers.size());
		new Thread (new Runnable() {
			@Override
			public void run() {
				for (CarabiAppServer server: servers) {
					if (!server.isEnabled()) {
						continue;
					}
					logger.log(Level.FINE, "send to server {0}", server.getSysname());
					try {
						eventerSingleRequestResponse(server, eventPackage, new Holder<>(CarabiEventType.fireEvent.getCode()), false);
					} catch (IOException ex) {
						Logger.getLogger(EventerBean.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}
		}).start();
	}
	
	/**
	 * Получение списка серверов, на которые надо отправить сообщение.
	 * Производится поиск всех серверов, где есть активные сессии пользователя.
	 * Если пользователь не задан -- берутся все сервера (broadcast).
	 * @param login логин пользователя-адресата или пустое значение, если слать всем.
	 * @throws CarabiException если нет пользователя с именем из параметра login 
	 */
	private List<CarabiAppServer> getTargetServers(String login) throws CarabiException {
		TypedQuery<CarabiAppServer> getSevers;
		if (login == null || login.equals("")) {
			getSevers = em.createNamedQuery("getAllServers", CarabiAppServer.class);
		} else {
			CarabiUser user = usersController.findUser(login);
			getSevers = em.createNamedQuery("getAllUserSevers", CarabiAppServer.class);
			getSevers.setParameter("user", user);
			GregorianCalendar calendar = new GregorianCalendar();
			calendar.add(GregorianCalendar.SECOND, -Settings.SESSION_LIFETIME);
			getSevers.setParameter("newer_than", calendar.getTime());
		}
		return getSevers.getResultList();
	}
	
	/**
	 * Подготовка данных для отправки функцией fireEvent.
	 * JSON-сериализация и представление в виде байтов
	 * @param schema
	 * @param login
	 * @param eventcode
	 * @param message
	 * @return 
	 */
	private byte[] prepareFireEventPackage(String schema, String login, short eventcode, String message) throws UnsupportedEncodingException, CarabiException {
		JsonObjectBuilder packageBuilder = Json.createObjectBuilder();
		packageBuilder.add("schema", schema);
		packageBuilder.add("login", login);
		packageBuilder.add("eventcode", eventcode);
		packageBuilder.add("message", message);
		String eventPackageJson = packageBuilder.build().toString();
		String eventPackageEncrypted;
		try {
			eventPackageEncrypted = CarabiFunc.encrypt(eventPackageJson);
		} catch (GeneralSecurityException ex) {
			Logger.getLogger(EventerBean.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiException("Encryption error", ex);
		}
		byte[] result = eventPackageEncrypted.getBytes("UTF-8");
		if (result.length > 10240) { //TODO Отправлять такие пакеты частями (если исходное сообщение достаточно короткое)
			throw new CarabiException("Prepared message too long (" + result.length + ")");
		}
		return result;
	}
	
	/**
	 * Отправка одного запроса на Eventer и получение ответа
	 * @param targetServer
	 * @param eventMessage
	 * @param code
	 * @return 
	 */
	public String eventerSingleRequestResponse(CarabiAppServer targetServer, String eventMessage, Holder<Short> code, boolean waitResponse) throws IOException {
		return EventerBean.this.eventerSingleRequestResponse(targetServer, eventMessage.getBytes("UTF-8"), code, waitResponse);
	}
	
	public String eventerSingleRequestResponse(CarabiAppServer targetServer, byte[] eventPackage, Holder<Short> code, boolean waitResponse) throws IOException {
		ByteBuffer shortBuffer;
		String computer;
		//Если мы находимся на том компьютере, куда шлём событие --
		//используем loopback, т.к. сервер может быть за NAT-ом.
		//Порт снаружи и внутри предполагается один.
		if (Settings.getCurrentServer().equals(targetServer)) {
			computer = "127.0.0.1";
		} else {
			computer= targetServer.getComputer();
		}
		try (Socket eventerSocket = new Socket(computer, targetServer.getEventerPort())) {
			eventerSocket.setKeepAlive(true);
			eventerSocket.setSoTimeout(10000);
			logger.log(Level.FINE, "created new Socket({0} ({1}), {2})", new Object[]{targetServer.getComputer(), computer, targetServer.getEventerPort()});
			shortBuffer = java.nio.ByteBuffer.allocate(2);
			shortBuffer.putShort(code.value);
			try (OutputStream outputStream = eventerSocket.getOutputStream()) {
				outputStream.write(shortBuffer.array());
				outputStream.write(eventPackage);
				shortBuffer.clear();
				outputStream.write(0);
				outputStream.flush();
				if (waitResponse) {
					InputStream inputStream = eventerSocket.getInputStream();
					int codeRes = inputStream.read() * 256;
					codeRes += inputStream.read();
					code.value = (short)codeRes;
					byte[] buffer = new byte[10240];
					int i = 0;
					int b = 0;
					while((b = inputStream.read()) > 0) {
						buffer[i] = (byte)b;
						i++;
					}
					return new String(buffer, 0, i, "UTF-8");
				} else {
					return null;
				}
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Error on eventerSingleRequestResponse to " + computer + ":" + targetServer.getEventerPort(), e);
			}
		}
		return null;
	}
	
}
