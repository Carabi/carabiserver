package ru.carabi.server.rest;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.enterprise.context.RequestScoped;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.oracle.QueryStorageBean;

/**
 * REST Service, выдающий данные по маршрутным листам
 *
 * @author sasha<kopilov.ad@gmail.com>
 */
@Path("routeslist")
@RequestScoped
public class RoutesListResource {
	private static final Logger logger = Logger.getLogger(RoutesListResource.class.getName());

	@Context
	private UriInfo context;
	@EJB private UsersControllerBean usersController;
	@EJB private QueryStorageBean queryStorage;
	
	
	public RoutesListResource() {
	}

	/**
	 * Выдача детализированного маршрутного листа для водителей
	 * @return an instance of java.lang.String
	 */
	@GET
	@Produces("application/json")
	public String getJson() {
		String token = context.getQueryParameters().getFirst("token");
		if (token == null) {
			return "{\"status\":\"fail\", \"error\":\"no input token\"}";
		}
		
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			
			logger.log(Level.FINE, "RoutesListResource: Creating route list for {0}", token);
			ArrayList<LinkedHashMap<String, ?>> traceInfoList = queryStorage.runOutonlyQuery(logon, "GET_TRACE2_INFO_LIST", Integer.MAX_VALUE);
			if (traceInfoList.isEmpty()) {
				return "{\"status\":\"ok\",\"list\":[]}";
			}
			logger.log(Level.FINE, "RoutesListResource: all data collected for {0}", token);
			JsonObject data = Json.createObjectBuilder()
					.add("status", "ok")
					.add("list", createTraceInfoJsonList(logon, traceInfoList))
					.add("sms", getSmsList(logon))
					.add("reasons", getReasonsList(logon))
					.add("managers", getManagersInfoList(logon))
					.build();
			logger.log(Level.FINE, "RoutesListResource: Route list is ready for {0}", token);
			return data.toString();
		} catch (CarabiException | SQLException ex) {
			logger.log(Level.SEVERE, "operation failed: ", ex);
			return "{\"status\":\"fail\", \"error\":\"operation with token '" + token +"' failed with message '" + ex.getMessage() + "'\"}";
		}
	}
	
	/**
	 * Создание подробного маршрутного листа из краткой выборки маршрутов.
	 * @param traceInfoList
	 * @return
	 * @throws CarabiException
	 * @throws SQLException 
	 */
	private synchronized JsonArrayBuilder createTraceInfoJsonList(UserLogon logon, ArrayList<LinkedHashMap<String, ?>> traceInfoList) throws CarabiException, SQLException {
		//Список текущих маршрутных листов водителя (обычно выдаётся один,
		//редко два: сегодняшний и завтрашний)
		Map<Object, ArrayList<LinkedHashMap<String, ?>>> traceInfoIndex = Utls.createIndex(traceInfoList, "ID");
		
		//Список пунктов назначения водителя
		ArrayList<LinkedHashMap<String, ?>> traceChInfoList = queryStorage.runOutonlyQuery(logon, "GET_TRACE2_CH_INFO_LIST", Integer.MAX_VALUE);
		
		//Список заданий водителю по пунктам назначения
		ArrayList<LinkedHashMap<String, ?>> traceOrdersList = queryStorage.runOutonlyQuery(logon, "GET_AIM3_INFO_LIST", Integer.MAX_VALUE);
		Map<?, ArrayList<LinkedHashMap<String, ?>>>traceOrdersIndex = Utls.createIndex(traceOrdersList, "TRACE_CH_ID");
		
		//Список контактных лиц по пунктам назначения
		ArrayList<LinkedHashMap<String, ?>> traceContactList = queryStorage.runOutonlyQuery(logon, "GET_CONTACT_INFO_LIST", Integer.MAX_VALUE);
		Map<?, ArrayList<LinkedHashMap<String, ?>>>traceContactIndex = Utls.createIndex(traceContactList, "TRACE_CH_ID");
		
		JsonArrayBuilder list = Json.createArrayBuilder();
		for (LinkedHashMap<String, ?> traceChInfo : traceChInfoList) {//Построение списка пунктов
			JsonObjectBuilder traceChJson = Utls.mapToJson(traceChInfo, putToList);
			Object traceId = traceChInfo.get("TRACE_ID");
			//Добавление данных об общем листе
			traceChJson.add("TRACE_NUMBER", (String)traceInfoIndex.get(traceId).get(0).get("TRACE_NUMBER"));
			traceChJson.add("TRACE_DATE", (String)traceInfoIndex.get(traceId).get(0).get("TRACE_DATE"));
			//Добавление вложенных элементов
			Object traceChId = traceChInfo.get("TRACE_CH_ID");
			//Добавление заданий в данном пункте
			ArrayList<LinkedHashMap<String, ?>> traceOrders = traceOrdersIndex.get(traceChId);
			addAimOrdersDetails(logon, traceOrders);
			
			JsonArrayBuilder traceOrdersJson = Utls.tableToJson(traceOrders);
			traceChJson.add("goals", traceOrdersJson);
			//Добавление контактов в данном пункте
			ArrayList<LinkedHashMap<String, ?>> traceContact = traceContactIndex.get(traceChId);
			JsonArrayBuilder traceContactJson = Utls.tableToJson(traceContact);
			traceChJson.add("contacts", traceContactJson);
			//размещение сформированного пункта в общем списке
			list.add(traceChJson);
		}
		return list;
	}
	
	private final String[] putToList = {"AIMS", "COMMENTS", "COMPANY", "FULL_ADDRESS", "IS_NEW", "LATITUDE", "LONGITUDE", "SHORT_ADDRESS", "TRACE_CH_ID", "TRACE_CH_STATUS", "TRACE_ID", "WORK_TIME"};
	
	private JsonArrayBuilder getSmsList(UserLogon logon) throws CarabiException, SQLException {
		ArrayList<LinkedHashMap<String, ?>> smsListInfo = queryStorage.runSmallQuery(logon, "GET_VOCAB_LIST", "TRACE_INFORM_TEXT", Integer.MAX_VALUE);
		JsonArrayBuilder smsList = Json.createArrayBuilder();
		for (LinkedHashMap<String, ?> smsInfo: smsListInfo) {
			String sms = smsInfo.get("STRING_VALUE").toString();
			smsList.add(sms);
		}
		return smsList;
	}
	
	private JsonArrayBuilder getReasonsList(UserLogon logon) throws CarabiException, SQLException {
		ArrayList<LinkedHashMap<String, ?>> reasonsListInfo = queryStorage.runSmallQuery(logon, "GET_VOCAB_LIST", "TRACE_REASON_UNDONE", Integer.MAX_VALUE);
		return Utls.tableToJson(reasonsListInfo);
	}
	
	private JsonArrayBuilder getManagersInfoList(UserLogon logon) throws CarabiException, SQLException {
		//String traceId = traceInfoList.get(0).get("ID").toString();
		ArrayList<LinkedHashMap<String, ?>> managersInfoList = queryStorage.runOutonlyQuery(logon, "GET_MANAGERS_DISPATCHER_LIST", Integer.MAX_VALUE);
		return Utls.tableToJson(managersInfoList);
	}
	
	/**
	 * Добавление деталей к некоторым задачам.
	 * На данный момент: только к заявкам на ремонт.
	 * @param traceOrders Массив заданий в точке маршрута
	 */
	private void addAimOrdersDetails(UserLogon logon, ArrayList<LinkedHashMap<String, ? extends Object>> traceOrders) throws CarabiException, SQLException {
		if (traceOrders == null) return;
		for (LinkedHashMap<String, ? extends Object> traceOrder: traceOrders) {
			String orderType = (String)traceOrder.remove("ORDER_TYPE");
			Object orderIDObj = traceOrder.remove("ORDER_ID");
			traceOrder.remove("ZAKAZ_TYPE");
			Object zakazIDObj = traceOrder.remove("ZAKAZ_ID");
			Object goalIDObj = traceOrder.get("GOAL_ID");
			String goalID = goalIDObj.toString();
			if ("ORDER_MASTER".equals(orderType) && ("22".equals(goalID) || "27".equals(goalID))) { //Заявка на ремонт, выезд специалиста
				addMasterInfo(logon, orderIDObj, zakazIDObj, traceOrder);
			}
		}
	}

	private void addMasterInfo(UserLogon logon, Object orderIDObj, Object zakazIDObj, LinkedHashMap<String, ? extends Object> traceOrder) throws CarabiException, SQLException {
		String orderID = orderIDObj.toString();
		String zakazID = zakazIDObj.toString();
		//Детальный состав работ
		ArrayList<LinkedHashMap<String, ?>> orderMasterDetails = getDetailsByIndex(logon, "GET_ZAKAZ_MASTER_INFO_DETAIL_LIST", "ordersMasterDetails", "ID", zakazID);
		//Все фотографии принтеров для текущего пользователя
		ArrayList<LinkedHashMap<String, ?>>photosList = getDetailsByIndex(logon, "GET_ZAKAZ_MASTER_ATTACH_LIST", "ordersAttachedPhotos", "ID", zakazID);
		
		LinkedHashMap<String, Object> orderMasterInfo = new LinkedHashMap<>();
		orderMasterInfo.put("ORDER_ID", orderID);
		orderMasterInfo.put("PRINTER_IN_ORDER_ID", zakazID);
		//ArrayList<LinkedHashMap<String, ?>> orderMasterDetails = queryStorage.runSmallQuery(logon, "GET_AIM3_ORDER_MASTER_INFO_DETAILS", zakazID, Integer.MAX_VALUE);
		LinkedHashMap<String, ?> orderMasterDetail = orderMasterDetails.get(0);
		orderMasterDetail.remove("ID");
		for (Entry<String, ?> entry: orderMasterDetail.entrySet()) {
			orderMasterInfo.put(entry.getKey(), entry.getValue());
		}
		ArrayList<LinkedHashMap<String, ?>> orderMasterHistory = queryStorage.runSmallQuery(logon, "GET_AIM3_ORDER_MASTER_INFO_HISTORY", zakazID, Integer.MAX_VALUE);
		orderMasterInfo.put("history", orderMasterHistory);
		//ArrayList<LinkedHashMap<String, ?>> photosList = queryStorage.runSmallQuery(logon, "GET_ATTACHES_LIST", zakazID, Integer.MAX_VALUE);
		orderMasterInfo.put("photos", photosList);
		((LinkedHashMap<String, Object>)traceOrder).put("orderMasterInfo", orderMasterInfo);
	}
	
	//кеш запросов для доп. информации
	private Map<String, ArrayList<LinkedHashMap<String, ?>>> detailsCachedQueries = new ConcurrentHashMap<>();
	//кеш запросов, сгруппированных по ID заказа
	private Map<String, Map<String, ArrayList<LinkedHashMap<String, ?>>>> detailsCachedQueriesIndexed = new ConcurrentHashMap<>();
	
	private ArrayList<LinkedHashMap<String, ?>> getDetailsByIndex(UserLogon logon, String queryName, String cacheName, String indexField, String indexValue) throws CarabiException, SQLException {
		ArrayList<LinkedHashMap<String, ?>> result = getIndexedDetails(logon, queryName, cacheName, indexField).get(indexValue);
		if (result == null) {
			return new ArrayList<>();
		} else {
			return result;
		}
	}
	
	private Map<String, ArrayList<LinkedHashMap<String, ?>>> getIndexedDetails(UserLogon logon, String queryName, String cacheName, String indexField) throws CarabiException, SQLException {
		Map<String, ArrayList<LinkedHashMap<String, ?>>> result = detailsCachedQueriesIndexed.get(cacheName);
		if (result == null) {
			ArrayList<LinkedHashMap<String, ?>> details;
			if (detailsCachedQueries.containsKey(cacheName)) {
				details = detailsCachedQueries.get(cacheName);
			} else {
				details = queryStorage.runOutonlyQuery(logon, queryName, Integer.MAX_VALUE);
				detailsCachedQueries.put(cacheName, details);
			}
			result = new ConcurrentHashMap<>();
			for (LinkedHashMap<String, ?> row: details) {
				String index = row.get(indexField).toString();
				ArrayList<LinkedHashMap<String, ?>> indexedRows;
				if (result.containsKey(index)) {
					indexedRows = result.get(index);
				} else { 
					indexedRows = new ArrayList<>();
				}
				indexedRows.add(row);
				result.put(index, indexedRows);
			}
			detailsCachedQueriesIndexed.put(cacheName, result);
		}
		return result;
	}
}
