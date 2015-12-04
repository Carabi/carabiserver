package ru.carabi.server.rest;

import java.io.StringReader;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MultivaluedMap;
import javax.xml.ws.Holder;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.kernel.GuestBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.UsersPercistenceBean;
import ru.carabi.server.kernel.oracle.QueryParameter;
import ru.carabi.server.kernel.oracle.QueryStorageBean;

/**
 * REST Web Service
 *
 * @author sasha
 */
@Path("run_stored_query")
public class RunStoredQuery {
	
	@EJB private GuestBean guest;
	@EJB private UsersPercistenceBean usersPercistence;
	@EJB private UsersControllerBean usersController;
	@EJB private QueryStorageBean queryStorage;
	
	
	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/json")
	public JsonObject runQuery(
			@QueryParam("token") String token,
			@QueryParam("login") String login,
			@QueryParam("password_hash") String passwordHash,
			@QueryParam("query_sysname") String queryName,
			MultivaluedMap<String, String> formParams
		) {
		JsonObjectBuilder parametersData = Json.createObjectBuilder();
		for (Map.Entry<String, List<String>> parameter: formParams.entrySet()) {
			parametersData.add(parameter.getKey(), parameter.getValue().get(0));
		}
		return runQuery(token, login, passwordHash, queryName, parametersData.build());
	}
	
	@POST
	@Consumes("application/json")
	@Produces("application/json")
	public JsonObject runQuery(
			@QueryParam("token") String token,
			@QueryParam("login") String login,
			@QueryParam("password_hash") String passwordHash,
			@QueryParam("query_sysname") String queryName,
			JsonObject parametersData
		) {
		boolean temporarySession = false;
		try {
			if (StringUtils.isEmpty(token)) {
				CarabiUser user = usersPercistence.findUser(login);
				Holder<String> schema = new Holder<>();
				Holder<String> tokenTmp = new Holder<>();
				guest.registerUserLight(user, passwordHash, "Temporary session (run stored query REST)", false, true, new Properties(), schema, tokenTmp);
				token = tokenTmp.value;
				temporarySession = true;
			}
			UserLogon userLogon = usersController.getUserLogon(token);
			Map<String, QueryParameter> parameters = new HashMap<>();
			for (Entry<String, JsonValue> parameterData: parametersData.entrySet()) {
				QueryParameter parameter = new QueryParameter();
				parameter.setIsIn(1);
				parameter.setName(parameterData.getKey());
				JsonValue value = parameterData.getValue();
				switch (parameterData.getValue().getValueType()) {
					case STRING:
						parameter.setValue(((JsonString)value).getString());
						break;
					case NUMBER:
						parameter.setValue(value.toString());
						break;
					case NULL:
						parameter.setValue(null);
						parameter.setIsNull(1);
						break;
					default:
						throw new CarabiException("Unsupported Json type");
				}
				parameters.put(parameter.getName(), parameter);
			}
			queryStorage.runQuery(userLogon, queryName, parameters, -Integer.MAX_VALUE);
			JsonObjectBuilder result = Json.createObjectBuilder();
			for (Entry<String, QueryParameter> parameterNameValue: parameters.entrySet()) {
				QueryParameter parameter = parameterNameValue.getValue();
				JsonValue parameterJson = wrapJson(parameter);
				result.add(parameterNameValue.getKey(), parameterJson);
			}
			return result.build();
		} catch (CarabiException | SQLException ex) {
			Logger.getLogger(RunStoredQuery.class.getName()).log(Level.SEVERE, null, ex);
			JsonObjectBuilder result = Json.createObjectBuilder();
			result.add("error", ex.getMessage());
			return result.build();
		} finally {
			if (temporarySession) {
				usersController.removeUserLogon(token, true);
			}
		}
	}
	
	private JsonValue wrapJson(QueryParameter queryParameter) throws SQLException {
		if ("CURSOR".equals(queryParameter.getType())) {
			Map cursorData = (Map) queryParameter.getValueObject();
			JsonObjectBuilder parameterJson = Utls.mapToJson(cursorData);
			parameterJson.add("queryTag", queryParameter.getValue());
			return parameterJson.build();
		} else if ("CLOB".equals(queryParameter.getType())) {
			oracle.sql.CLOB clob = (oracle.sql.CLOB)queryParameter.getValueObject();
			String stringValue = clob.stringValue();
			if ("CLOB_AS_CURSOR".equals(queryParameter.getValue())) {
				return Json.createReader(new StringReader(stringValue)).readObject();
			} else {
				return Json.createObjectBuilder().add("tmp", stringValue).build().getJsonString("tmp");
			}
		} else if ("NUMBER".equals(queryParameter.getType())) {
			String stringValue = queryParameter.getValue();
			if (org.apache.commons.lang3.math.NumberUtils.isDigits(stringValue)) {
				return Json.createObjectBuilder().add("tmp", new Long(stringValue)).build().getJsonNumber("tmp");
			} else {
				return Json.createObjectBuilder().add("tmp", new Double(stringValue)).build().getJsonNumber("tmp");
			}
		} else {
			String stringValue = queryParameter.getValue();
			JsonString jsonString = Json.createObjectBuilder().add("tmp", stringValue).build().getJsonString("tmp");
			return jsonString;
		}
	}
}
