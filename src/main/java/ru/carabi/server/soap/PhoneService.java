package ru.carabi.server.soap;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Phone;
import ru.carabi.server.kernel.AdminBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.logging.CarabiLogging;

/**
 *
 * @author sasha
 */
@WebService(serviceName = "PhoneService")
public class PhoneService {
	Logger logger = CarabiLogging.getLogger(PhoneService.class);
	
	@EJB private UsersControllerBean uc;
	@EJB private AdminBean admin;
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	/**
	 * Получить все телефоны пользователя.
	 * Возвращает все телефоны, свои или другого пользователя.
	 * @param token токен
	 * @param login логин пользователя, телефоны которого нужны
	 * @return JSON-массив объектов вида [{id: id,
	 * type: тип телефона (SIP, mobile, simple),
	 * countryCode: код страны (7 для России),
	 * regionCode: код региона или оператора (812, 911 и т.п.),
	 * mainNumber: номер телефона (обычно семизначный),
	 * suffix: дополнительный номер
	 * }]
	 * @throws CarabiException 
	 */
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
				Utls.addJsonNumber(phoneJson, "id", phone.getId());
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
	
	/**
	 * SIP-номера текущего пользователя с указанием базы ORACLE, рядом с которой находится АТС
	 * @param token
	 * @return JSON-массив объектов вида [{id:id
	 * countryCode: код страны (7 для России),
	 * regionCode: код региона или оператора (812, 911 и т.п.),
	 * mainNumber: номер телефона (обычно семизначный),
	 * suffix: дополнительный номер
	 * }]
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "getMySip")
	public String getMySip(
			@WebParam(name = "token") String token
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			CarabiUser user = logon.getUser();
			if (user == null) {
				return null;
			}
			Collection<Phone> phonesList = user.getPhonesList();
			JsonArrayBuilder phonesListJson = Json.createArrayBuilder();
			for (Phone phone: phonesList) {
				if (!phone.getPhoneType().getSysname().equals("SIP")) {
					continue;
				}
				JsonObjectBuilder phoneJson = Json.createObjectBuilder();
				Utls.addJsonNumber(phoneJson, "id", phone.getId());
				Utls.addJsonNumber(phoneJson, "countryCode", phone.getCountryCode());
				Utls.addJsonNumber(phoneJson, "regionCode", phone.getRegionCode());
				Utls.addJsonNumber(phoneJson, "mainNumber", phone.getMainNumber());
				Utls.addJsonNumber(phoneJson, "suffix", phone.getSuffix());
				phonesListJson.add(phoneJson);
			}
			return phonesListJson.build().toString();
		}
	}
	
	/**
	 * Вызов звонка (прототип).
	 * @param token токен клиента
	 * @param phoneFromId ID SIP-телефона, с которого звоним (если у клиента их несколько) -- должен принадлежать клиенту
	 * @param phoneToId ID телефона, на который звоним
	 * @return
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "initCall")
	public String initCall(
			@WebParam(name = "token") String token,
			@WebParam(name = "phoneFromId") Long phoneFromId,
			@WebParam(name = "phoneToId") Long phoneToId
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			Phone phoneFrom = em.find(Phone.class, phoneFromId);
			Phone phoneTo = em.find(Phone.class, phoneToId);
			logger.log(Level.INFO, "init call from {0} to {1}", new Object[]{phoneFrom.toString(), phoneTo.toString()});
		}
		return "";
	}
	
	/**
	 * Перенаправление звонка (прототип).
	 * @param token токен клиента
	 * @param phoneFromId ID текущего SIP-телефона (если у клиента их несколько) -- должен принадлежать клиенту
	 * @param phoneToId ID телефона, на который перенаправляем звонок
	 * @return
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "redirectCall")
	public String redirectCall(
			@WebParam(name = "token") String token,
			@WebParam(name = "phoneFromId") Long phoneFromId,
			@WebParam(name = "phoneToId") Long phoneToId
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			Phone phoneFrom = em.find(Phone.class, phoneFromId);
			Phone phoneTo = em.find(Phone.class, phoneToId);
			logger.log(Level.INFO, "redirect call from {0} to {1}", new Object[]{phoneFrom.toString(), phoneTo.toString()});
		}
		return "";
	}
	
	/**
	 * Прерывание звонка (прототип).
	 * @param token токен клиента
	 * @param phoneId ID SIP-телефона, на котором вешаем трубку (если у клиента их несколько) -- должен принадлежать клиенту
	 * @return
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "terminateCall")
	public String terminateCall (
			@WebParam(name = "token") String token,
			@WebParam(name = "phoneId") Long phoneId
		) throws CarabiException {
		try (UserLogon logon = uc.tokenAuthorize(token, false)) {
			Phone phone = em.find(Phone.class, phoneId);
			logger.log(Level.INFO, "terminate call on {0}", phone.toString());
		}
		return "";
	}
}