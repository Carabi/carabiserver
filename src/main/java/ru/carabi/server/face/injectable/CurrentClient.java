package ru.carabi.server.face.injectable;

import java.io.Serializable;
import java.util.Properties;
import java.util.logging.Level;
import javax.enterprise.context.RequestScoped;
import javax.inject.Named;
import ru.carabi.server.UserLogon;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Данные о клиенте, использующем сервер через браузер.
 * @author sasha
 */

@Named
@RequestScoped
public class CurrentClient implements Serializable {
	
	private Properties properties = new Properties();
	
	private UserLogon userLogon;
	private String test;
	
//	public UserLogon getUserLogon() {
//		return userLogon;
//	}
	
	public boolean getIsAuthorized() {
		return userLogon != null;
	}
	
	public Properties getProperties() {
		return properties;
	}

	public String getTest() {
		CarabiLogging.getLogger(this).log(Level.INFO, "getTest: {0}", test);
		return test;
	}

	public void setTest(String test) {
		CarabiLogging.getLogger(this).log(Level.INFO, "setTest. before: {0}", this.test);
		this.test = test;
		CarabiLogging.getLogger(this).log(Level.INFO, "setTest. after: {0}", this.test);
	}
	
}
