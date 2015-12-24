package ru.carabi.server.kernel.sms;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;

/**
 * Доступ к API сервиса http://www.sms4b.ru
 * @author sasha<kopilov.ad@gmail.com>
 */
public class GateSms4B implements SmsServiceGate {
	ResourceBundle properties;
	
	@Override
	public void setProperties(ResourceBundle properties) {
		this.properties = properties;
	}

	@Override
	public void sendSmsMessage(String phoneNumber, String text, String sender) {
		try {
			List<NameValuePair> parameters = new ArrayList<>();
			parameters.add(new BasicNameValuePair("Login", properties.getString("sms4b_login")));
			parameters.add(new BasicNameValuePair("Password", properties.getString("sms4b_password")));
			String source;
			if (!StringUtils.isEmpty(sender)) {
				source = sender;
			} else {
				try {
					source = properties.getString("sms4b_sender");
				} catch (MissingResourceException e) {
					source = properties.getString("sms4b_login");
				}
			}
			parameters.add(new BasicNameValuePair("Source", source));
			parameters.add(new BasicNameValuePair("Phone", phoneNumber));
			parameters.add(new BasicNameValuePair("Text", text));
			HttpPost request = new HttpPost("https://sms4b.ru/ws/sms.asmx/SendSMS");
			HttpEntity body = new UrlEncodedFormEntity(parameters);
			request.setEntity(body);
			CloseableHttpClient httpClient = HttpClients.createSystem();
			CloseableHttpResponse response = httpClient.execute(request);
			
			response.close();
		} catch (UnsupportedEncodingException ex) {
			Logger.getLogger(GateSms4B.class.getName()).log(Level.SEVERE, null, ex);
		} catch (IOException ex) {
			Logger.getLogger(GateSms4B.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
}
