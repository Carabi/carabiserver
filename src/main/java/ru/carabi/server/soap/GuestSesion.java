package ru.carabi.server.soap;

import java.io.Serializable;
import javax.enterprise.context.SessionScoped;

/**
 * Контейнер для данных о клиенте при прохождении двухэтапной авторизации.
 * Данные сохраняются от первого этапа до второго, пока пользователь не
 * авторизовался в ядре.
 * @author sasha
 */
@SessionScoped
public class GuestSesion implements Serializable {
	private String timeStamp;
	private String login;
	private int schemaID;

	public String getTimeStamp() {
		return timeStamp;
	}

	public void setTimeStamp(String timeStamp) {
		this.timeStamp = timeStamp;
	}

	public String getLogin() {
		return login;
	}

	public void setLogin(String login) {
		this.login = login;
	}

	public int getSchemaID() {
		return schemaID;
	}

	public void setSchemaID(int schemaID) {
		this.schemaID = schemaID;
	}
	private String schemaName;

	public String getSchemaName() {
		return schemaName;
	}

	public void setSchemaName(String schemaName) {
		this.schemaName = schemaName;
	}
	
}
