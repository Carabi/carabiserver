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
 * Доступ к API сервиса http://sms.ru
 * @author sasha<kopilov.ad@gmail.com>
 */
public class GateSmsRu implements SmsServiceGate {
	ResourceBundle properties;
	
	@Override
	public void setProperties(ResourceBundle properties) {
		this.properties = properties;
	}

	@Override
	public void sendSmsMessage(String phoneNumber, String text, String sender) {
		try {
			String apiId = properties.getString("smsRu_apiId");
			List<NameValuePair> parameters = new ArrayList<>();
			parameters.add(new BasicNameValuePair("api_id", apiId));
			String from;
			if (!StringUtils.isEmpty(sender)) {
				from = sender;
			} else {
				try {
					from = properties.getString("smsRu_sender");
				} catch (MissingResourceException e) {
					from = "";
				}
			}
			if (!StringUtils.isEmpty(from)) {
				parameters.add(new BasicNameValuePair("from", from));
			}
			parameters.add(new BasicNameValuePair("to", phoneNumber));
			parameters.add(new BasicNameValuePair("text", text));
			HttpPost request = new HttpPost("http://sms.ru/sms/send");
			//HttpPost request = new HttpPost("http://netbeans.loc/test.php");
			HttpEntity body = new UrlEncodedFormEntity(parameters);
			request.setEntity(body);
			CloseableHttpClient httpClient = HttpClients.createSystem();
			CloseableHttpResponse response = httpClient.execute(request);
			
			response.close();
		} catch (UnsupportedEncodingException ex) {
			Logger.getLogger(GateSmsRu.class.getName()).log(Level.SEVERE, null, ex);
		} catch (IOException ex) {
			Logger.getLogger(GateSmsRu.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}
