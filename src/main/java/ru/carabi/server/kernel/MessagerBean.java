package ru.carabi.server.kernel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.Timer;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import ru.carabi.server.CarabiException;
import ru.carabi.server.CarabiOracleError;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.ConnectionSchema;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.kernel.oracle.QueryStorageBean;

/**
 *
 * @author sasha
 */
@Stateless
public class MessagerBean {
	
	@EJB private UsersControllerBean uc;
	@EJB private QueryStorageBean queryStorage;
	@EJB private ConnectionsGateBean connectionsGate;
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager em;
	
	/**
	 * Получение числа непрочитанных сообщений в текущей базе
	 * @param logon пользовательская сессия
	 * @return число непрочитанных сообщений
	 */
	public int countUnreadMessages(UserLogon logon) {
		try {
			OracleConnection connection = Utls.unwrapOracleConnection(logon.getConnection());
			return countUnreadMessages(connection, logon.getExternalId());
		} catch (SQLException | CarabiException ex) {
			Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
		}
		return 0;
	}

	/**
	 * Получение числа непрочитанных сообщений  пользователя во всех базах
	 * @param user пользователь
	 * @return число непрочитанных сообщений для каждой базы
	 */
	public Map<ConnectionSchema, Integer> collectUnreadMessages(CarabiUser user) {
		Map<ConnectionSchema, Integer> result = new HashMap<>();
		for (ConnectionSchema schema: user.getAllowedSchemas()) {
			try (Connection connection = connectionsGate.connectToSchema(schema)){
				Context ctx = new InitialContext();
				AuthorizeSecondary authorize = new AuthorizeSecondaryCarabi();
				long userID = authorize.getUserID(connection, user.getLogin());
				if (authorize.getUserID(connection, user.getLogin()) >= 0) {
					UserLogon logon = new UserLogon();
					logon.setUser(user);
					logon.setExternalId(userID);
					logon.setConnectionsGate(connectionsGate);
					logon.setSchema(schema);
					logon.setUsersController(uc);
					logon.setToken(user.getLogin() + "-" + schema.getSysname() + "-temp-" + new Date().toString());
					int unread = countUnreadMessages(Utls.unwrapOracleConnection(logon.getConnection()), logon.getExternalId());
					result.put(schema, unread);
				}
			} catch (CarabiException | NamingException | SQLException ex) {
				Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return result;
	}
	
	/**
	 * Автоматический сбор непрочитанных сообщений
	 */
//	@Schedule(minute="*/1", hour="*")
	public void collectUnreadMessages(Timer timer) {
		TypedQuery<CarabiUser> activeUser = em.createQuery (
			"select U from CarabiUser U where U.login = :login", 
			CarabiUser.class
		);
		Logger l = Logger.getLogger(this.getClass().getName());
		l.info("Count unread messages");
		JsonObjectBuilder result = Json.createObjectBuilder();
		JsonArrayBuilder logins = Json.createArrayBuilder();
		JsonArrayBuilder bases = Json.createArrayBuilder();
		JsonArrayBuilder counts = Json.createArrayBuilder();
		for (String login: uc.getActiveUsers()) {
			activeUser.setParameter("login", login);
			CarabiUser user = activeUser.getSingleResult();
			Map<ConnectionSchema, Integer> unreadMessages = collectUnreadMessages(user);
			for (ConnectionSchema schema: unreadMessages.keySet()) {
				logins.add(login);
				bases.add(schema.getName());
				counts.add(unreadMessages.get(schema));
			}
		}
		result.add("login", logins);
		result.add("base", bases);
		result.add("messages", counts);
		ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.Settings");
		String[] WebServersList = settings.getString("WebServersList").split(", ");
		for(String serversAddress: WebServersList) {
			try {
				URL url = new URL(serversAddress);
				URLConnection openConnection = url.openConnection();
				HttpURLConnection connection = (HttpURLConnection) openConnection;
				connection.setRequestMethod("POST");
				connection.setDoInput(true);
				connection.setDoOutput(true);
				OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream(), Charset.forName("UTF8"));
				out.append(result.toString());
				out.close();
				InputStreamReader isr = new InputStreamReader(connection.getInputStream());
				BufferedReader in = new BufferedReader(isr);
				String inputLine;
				while((inputLine = in.readLine()) != null) {
					l.info(inputLine);
				}
				in.close();
			} catch (MalformedURLException ex) {
				Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
			} catch (IOException ex) {
				Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}
	
	private int countUnreadMessages(OracleConnection connection, long userId) {
		try {
			String sql = "SELECT count(de.docevent_id) cnt FROM doc_events de\n" +
		"  WHERE (de.event_user = :EVENT_USER) AND\n" +
		"   (de.doceventkind_id = :EVENT_ID) AND\n" +
		"   (de.history = 0) AND (de.settled = 0)";
			OraclePreparedStatement statement = (OraclePreparedStatement) connection.prepareStatement(sql);
			statement.setIntAtName("EVENT_ID", Utls.getStatusID(connection, "received"));
			statement.setLongAtName("EVENT_USER", userId);
			ResultSet resultSet = statement.executeQuery();
			resultSet.next();
			int result = resultSet.getInt(1);
			resultSet.close();
			return result;
		} catch (SQLException ex) {
			Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
		}
		return 0;
	}
	
	public ArrayList<LinkedHashMap<String, ?>> getNotifyMessages(UserLogon logon) throws CarabiException, CarabiOracleError {
		try {
			return queryStorage.runSmallQuery(logon, "GET_NOTIFY_MESSAGES", "1", -Integer.MAX_VALUE);
		} catch (SQLException ex) {
			Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiOracleError(ex);
		}
	}
	
	public Map<ConnectionSchema, List<LinkedHashMap<String, ?>>> collectUnreadMessagesDetails(CarabiUser user) {
		Map<ConnectionSchema, List<LinkedHashMap<String, ?>>> result = new HashMap<>();
		for (ConnectionSchema schema: user.getAllowedSchemas()) {
			try (Connection connection = connectionsGate.connectToSchema(schema)){
				Context ctx = new InitialContext();
				AuthorizeSecondary authorize = new AuthorizeSecondaryCarabi();
				long userID = authorize.getUserID(connection, user.getLogin());
				if (userID >= 0) {
					UserLogon logon = new UserLogon();
					logon.setUser(user);
					logon.setExternalId(userID);
					logon.setConnectionsGate(connectionsGate);
					logon.setSchema(schema);
					logon.setUsersController(uc);
					logon.setToken(user.getLogin() + "-" + schema.getSysname() + "-temp-" + new Date().toString());
					List<LinkedHashMap<String, ?>> unread = getUnreadMessagesDetails(logon);
					result.put(schema, unread);
				}
			} catch (CarabiException | NamingException | SQLException ex) {
				Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return result;
	}

	private List<LinkedHashMap<String, ?>> getUnreadMessagesDetails(UserLogon ul) {
		try {
			return queryStorage.runSmallQuery(ul, "getUnreadMessageList", ""+ul.getExternalId(), -Integer.MAX_VALUE);
		} catch (SQLException | CarabiException ex) {
			Logger.getLogger(MessagerBean.class.getName()).log(Level.SEVERE, null, ex);
		}
		return null;
	}
	
	/**
	 * Отправка электронной почты через SMTP-сервер.
	 * @param from от кого (может быть пустым, заполняется из настроек)
	 * @param to кому
	 * @param subject тема письма
	 * @param text текст письма
	 */
	public void sendEmail(String from, String to, String subject, String text) {
		ResourceBundle settings = ResourceBundle.getBundle("ru.carabi.server.Settings");
		
		if (from == null || from.equals("")) {
			from = settings.getString("MailFrom");
		}
		String host = settings.getString("SMTPHost");
		
		Properties properties = System.getProperties();
		properties.setProperty("mail.smtp.host", host);
		// Get the default Session object.
		Session session = null;
		if ("true".equals(settings.getString("MailAuth"))) {
			properties.put("mail.smtp.auth", true);
			String smtpLogin = settings.getString("MailAuthLogin");
			String smtpPassword = settings.getString("MailAuthPassword");
			session = Session.getDefaultInstance(properties, new SimpleAuthenticator(smtpLogin, smtpPassword));
		} else {
			session = Session.getDefaultInstance(properties);
		}
		
		try {
			// Create a default MimeMessage object.
			MimeMessage message = new MimeMessage(session);

			// Set From: header field of the header.
			message.setFrom(new InternetAddress(from, true));

			// Set To: header field of the header.
			message.addRecipient(Message.RecipientType.TO, new InternetAddress(to, true));

			// Set Subject: header field
			message.setSubject(subject);

			// Now set the actual message
			message.setText(text);

			// Send message
			Transport.send(message);
			System.out.println("Отправлено");
		} catch (MessagingException mex) {
			Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "SendMail error", mex);
		}
	}

	private static class SimpleAuthenticator extends Authenticator {
		private String login, password;
		
		public SimpleAuthenticator(String login, String password) {
			this.login = login;
			this.password = password;
		}
		
		protected PasswordAuthentication getPasswordAuthentication(){
			return new PasswordAuthentication(login, password);
		}
		
	}
}
