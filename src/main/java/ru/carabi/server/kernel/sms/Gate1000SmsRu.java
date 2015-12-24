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
 * Доступ к API сервиса http://1000sms.ru
 * @author sasha<kopilov.ad@gmail.com>
 */
public class Gate1000SmsRu implements SmsServiceGate {
	ResourceBundle properties;
	
	@Override
	public void setProperties(ResourceBundle properties) {
		this.properties = properties;
	}

	@Override
	public void sendSmsMessage(String phoneNumber, String text, String sender) {
		try {
			//String apiId = properties.getString("smsRu_apiId");
			List<NameValuePair> parameters = new ArrayList<>();
			parameters.add(new BasicNameValuePair("method", "push_msg"));
			parameters.add(new BasicNameValuePair("email", properties.getString("1000smsRu_login")));
			parameters.add(new BasicNameValuePair("password", properties.getString("1000smsRu_password")));
			String senderName;
			if (!StringUtils.isEmpty(sender)) {
				senderName = sender;
			} else {
				try {
					senderName = properties.getString("sms4b_sender");
				} catch (MissingResourceException e) {
					senderName = "";
				}
			}
			if (!StringUtils.isEmpty(senderName)) {
				parameters.add(new BasicNameValuePair("sender_name", senderName));
			}
			parameters.add(new BasicNameValuePair("phone", phoneNumber));
			parameters.add(new BasicNameValuePair("text", text));
			parameters.add(new BasicNameValuePair("format", "JSON"));
			HttpPost request = new HttpPost("http://api.1000sms.ru/");
			//HttpPost request = new HttpPost("http://netbeans.loc/test.php");
			HttpEntity body = new UrlEncodedFormEntity(parameters);
			request.setEntity(body);
			CloseableHttpClient httpClient = HttpClients.createSystem();
			CloseableHttpResponse response = httpClient.execute(request);
			
			response.close();
		} catch (UnsupportedEncodingException ex) {
			Logger.getLogger(Gate1000SmsRu.class.getName()).log(Level.SEVERE, null, ex);
		} catch (IOException ex) {
			Logger.getLogger(Gate1000SmsRu.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
}
