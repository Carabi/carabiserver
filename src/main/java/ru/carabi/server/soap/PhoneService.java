package ru.carabi.server.soap;

import java.util.Collection;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Phone;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;

/**
 *
 * @author sasha
 */
@WebService(serviceName = "PhoneService")
public class PhoneService {
	
	@EJB private UsersControllerBean uc;
	@EJB private AdminBean admin;

	@WebMethod(operationName = "getUserPhones")
	public String getUserPhones(
			@WebParam(name = "token") String token,
			@WebParam(name = "login") String login
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			CarabiUser user;
			if (StringUtils.isEmpty(login)) {
				user = logon.getUser();
			} else {
				user= admin.findUser(login);
				if (user == null) {
					throw new CarabiException("user " + login + " not found");
				}
			}
			Collection<Phone> phonesList = user.getPhonesList();
			JsonArrayBuilder phonesListJson = Json.createArrayBuilder();
			for (Phone phone: phonesList) {
				JsonObjectBuilder phoneJson = Json.createObjectBuilder();
				Utls.addJsonObject(phoneJson, "type", phone.getPhoneType().getSysname());
				Utls.addJsonNumber(phoneJson, "countryCode", phone.getCountryCode());
				Utls.addJsonNumber(phoneJson, "regionCode", phone.getRegionCode());
				Utls.addJsonNumber(phoneJson, "mainNumber", phone.getMainNumber());
				Utls.addJsonNumber(phoneJson, "suffix", phone.getSuffix());
				phonesListJson.add(phoneJson);
			}
			return phonesListJson.build().toString();
		}
	}
}
