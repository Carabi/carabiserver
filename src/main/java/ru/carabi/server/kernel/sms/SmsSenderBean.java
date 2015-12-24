package ru.carabi.server.kernel.sms;
import javax.ejb.Singleton;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Phone;
import ru.carabi.server.entities.PhoneType;

/**
 * Ядровой модуль для отправки SMS-сообщений.
 * Функционал, непосредственно осуществляющий отправку, должен быть представлен
 * реализациями интерфейса {@link SmsServiceGate}
 * @author sasha<kopilov.ad@gmail.com>
 */
@Singleton
public class SmsSenderBean {
	SmsServiceGate serviceGate = SmsServiceGateFactory.createServiceGate();
	
	/**
	 * Отправить SMS-сообщение указанному пользователю.
	 * Получатель должен иметь в базе мобильный телефон.
	 * Сообщение отправляется на первый из найденных мобильных телефонов пользователя
	 * @param logon сессия текущего пользователя
	 * @param receiver получатель
	 * @param text текст сообщения
	 */
	public void sendSmsMessage(UserLogon logon, CarabiUser receiver, String text, String sender) throws CarabiException {
		for (Phone phone: receiver.getPhonesList()) {
			if (phone.getPhoneType() != null && PhoneType.MOBILE.equals(phone.getPhoneType().getSysname())) {
				sendSmsMessage(logon, phone, text, sender);
				return;
			}
		}
		throw new CarabiException(new IllegalStateException("User does not have mobile phone"));
	}
	
	/**
	 * Отправить SMS-сообщение на указанный телефон.
	 * Поле phone.sysname должно иметь значение mobile.
	 * @param logon сессия текущего пользователя
	 * @param phone телефон, на который отправлять сообщение
	 * @param text текст сообщения
	 * @throws ru.carabi.server.CarabiException При отправке на немобильный телефон
	 */
	public void sendSmsMessage(UserLogon logon, Phone phone, String text, String sender) throws CarabiException {
		if (PhoneType.MOBILE.equals(phone.getPhoneType().getSysname())) {
			StringBuilder phoneStr = new StringBuilder();
			phoneStr.append(phone.getCountryCode());
			phoneStr.append(phone.getRegionCode());
			phoneStr.append(phone.getMainNumber());
			sendSmsMessage(logon, phoneStr.toString(), text, sender);
		} else {
			throw new CarabiException(new IllegalArgumentException("can not send SMS to this phone (not mobile)"));
		}
	}
	
	/**
	 * Отправить SMS-сообщение на указанный номер.
	 * @param logon сессия текущего пользователя
	 * @param phone телефонный номер (обычно десятизначный) без доп. символов
	 * @param text текст сообщения
	 * @param sender номер или название отправителя (может не поддерживаться сервисом отправки)
	 */
	public void sendSmsMessage(UserLogon logon, String phone, String text, String sender) throws CarabiException {
		logon.assertAllowed("SMS_SENDING");
		serviceGate.sendSmsMessage(phone, text, sender);
	}
}
