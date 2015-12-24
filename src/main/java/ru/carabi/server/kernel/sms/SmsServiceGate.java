package ru.carabi.server.kernel.sms;

import java.util.ResourceBundle;

/**
 * Обобщённый интерфейс для доступа к сервисам отправки SMS-сообщений.
 * @author sasha<kopilov.ad@gmail.com>
 */
public interface SmsServiceGate {
	/**
	 * Установить настройки для подключения.
	 * @param properties настройки (ключи у разных сервисов разные)
	 */
	public void setProperties(ResourceBundle properties);
	
	/**
	 * Отправить SMS-сообщение на номер phoneNumber с содержимым text.
	 * Если сервис поддерживает отправку от имени различных отправителей --
	 * указать отправителя, иначе оставить параметр sender пустым.
	 * @param phoneNumber номер телефона без разделителей (обычно 10-значный)
	 * @param text текст сообщения
	 * @param sender отправитель (номер или название)
	 */
	public void sendSmsMessage(String phoneNumber, String text, String sender);
}
