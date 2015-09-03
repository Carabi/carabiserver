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
public class AuthSesion implements Serializable {
	private String timeStamp;
	private String login;
	
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
}
