package ru.carabi.server.kernel.sms;

import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Создание объекта, реализующего функционал {@link SmsServiceGate}
 * и обеспечивающего доступ к конкретному сервису (или устройству) для отправки
 * SMS-сообщений в соответствии с настройками.
 * @author sasha<kopilov.ad@gmail.com>
 */
public class SmsServiceGateFactory {
	private static final Logger logger = CarabiLogging.getLogger(SmsServiceGateFactory.class);
	public static final ResourceBundle smsGatesSettings = ResourceBundle.getBundle("ru.carabi.server.SmsGates");
	
	static SmsServiceGate createServiceGate() {
		String smsServiceGateName = smsGatesSettings.getString("currentSmsServiceGate");
		try {
			SmsServiceGate smsServiceGate = (SmsServiceGate) Class.forName(smsServiceGateName).newInstance();
			smsServiceGate.setProperties(smsGatesSettings);
			return smsServiceGate;
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
			logger.log(Level.SEVERE, null, ex);
		}
		return null;
	}
	
}
