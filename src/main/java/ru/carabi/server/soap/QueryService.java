package ru.carabi.server.soap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.json.JsonObjectBuilder;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.xml.ws.Holder;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.CarabiOracleError;
import ru.carabi.server.CarabiOracleMessage;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.kernel.Cache;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.oracle.CursorFetcherBean;
import ru.carabi.server.kernel.oracle.QueryParameter;
import ru.carabi.server.kernel.oracle.QueryStorageBean;
import ru.carabi.server.kernel.oracle.SqlQueryBean;

/** 
 * Сервис для осуществления запросов к БД Oracle.
 * Позволяет осуществить произвольные запросы на Carabi XML и PL/SQL-запросы,
 * сохранённые в служебной БД сервера приложений через сервис {@link DeveloperService}
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@WebService(serviceName = "QueryService")
public class QueryService {
	private static final Logger logger = Logger.getLogger(QueryService.class.getName());
	
	@EJB private UsersControllerBean usersController;
	@EJB private SqlQueryBean sqlQuery;
	@EJB private Cache<ArrayList<QueryParameter>> cache;
	@EJB private CursorFetcherBean cursorFetcher;
	@EJB private QueryStorageBean queryStorage;
	private int MAX_NUMBER_OF_WEBUSER_DOCTYPES = 10000;
	private int MAX_NUMBER_OF_DOCUMENT_FILTERS = 1000;
	
	// convert InputStream to String
	private static String getStringFromInputStream(InputStream is) throws IOException {
		BufferedReader br = null;
		try {
			StringBuilder sb = new StringBuilder();
			br = new BufferedReader(new InputStreamReader(is));
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		} finally {
			if (br != null) {
				br.close();
			}
		}
	}	
	
	/**
	 * Читает строки из прокрутки (открытого ранее запроса).
	 * Функция ищет прокрутку с номером <code>queryTag</code>, открытый пользователем
	 * с токеном <code>token</code>, находящийся на позиции <code>startPos</code>
	 * и считывает из него <code>fetchCount</code> записей.
	 * @param token авторизационный токен
	 * @param startPos номер записи, начиная с которой выводим результат
	 * @param fetchCount количество записей, возвращаемых в <code>listJson</code>
	 * @param queryTag номер прокрутки
	 * @param listJson выходной параметр &ndash; выдача в виде JSON-массива
	 * @param endpos выходной параметр &ndash; текущая позиция в выдаче после выполнения функции
	 * @return <ul>
	 *		<li>при успешном выполнении &ndash; число строк в выдаче;</li>
	 *		<li>{@link Settings#SQL_EOF}, если не найдена соответствующая прокрутка;</li>
	 *		<li>{@link Settings#SQL_ERROR}, если произошлёл сбой при считывании записей;</li>
	 *		<li>{@link Settings#NO_SUCH_USER} если подан незарегистрированный или устаревший токен.</li>
	 * </ul>
	 * @see #docSearchXml(java.lang.String, java.lang.String, java.lang.String, int, int, int, java.util.ArrayList, java.util.ArrayList, java.lang.String, java.lang.String, int, int, int, int, javax.xml.ws.Holder, javax.xml.ws.Holder, javax.xml.ws.Holder, javax.xml.ws.Holder, javax.xml.ws.Holder)
	 * @see #runStoredQuery(java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder)
	 */
	@WebMethod(operationName = "fetchNext")
	public int fetchNext(
			@WebParam(name = "token") String token,
			@WebParam(name = "startPos") int startPos,
			@WebParam(name = "fetchCount") int fetchCount,
			@WebParam(name = "queryTag") int queryTag,
			@WebParam(name = "listJson", mode= WebParam.Mode.OUT) Holder<String> listJson,
			@WebParam(name = "endpos", mode= WebParam.Mode.OUT) Holder<Integer> endpos
		) throws CarabiException {
		logger.log(
			Level.FINE,
			" fetchNext token={0}, startPos={1}, fetchCount={2}, queryTag={3}, listJson={4}, endpos={5}", 
			new Object[] {token, startPos, fetchCount, queryTag}
		);
		Holder<ArrayList<ArrayList<?>>> list = new Holder<>();
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			int result = cursorFetcher.fetchNext(logon, queryTag, startPos, fetchCount, list, endpos);
			listJson.value = Utls.listToJson(list.value).build().toString();
			logger.log(Level.INFO, "fetch result = {0}", endpos.value);
			return result;
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiOracleError(ex);
		} catch (Throwable e) {
			logger.log(Level.SEVERE, "Unknown error:", e);
			throw e;
		}
	}
	
	/**
	 * Закрывает прокрутку.
	 * @param token авторизационный токен
	 * @param queryTag номер прокрутки
	 * @return <ul>
	 *		<li>0 при успешном выполнении;</li>
	 *		<li>{@link Settings#SQL_ERROR}, если произошлёл сбой при закрытии;</li>
	 *		<li>{@link Settings#NO_SUCH_USER} если подан незарегистрированный или устаревший токен.</li>
	 * </ul>
	 */
	@WebMethod(operationName = "closeFetch")
	public int closeFetch(
			@WebParam(name = "token") String token,
			@WebParam(name = "queryTag") int queryTag
		) throws CarabiOracleError, CarabiException {
		logger.log(Level.FINE, "closeFetch token={0}, queryTag={1}", new Object[]{token, queryTag});
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			cursorFetcher.closeFetch(logon, queryTag);
			return 0;
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiOracleError(ex);
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw ex;
		}
	}
	
	/**
	 * Закрывает все прокрутки клиента.
	 * @param token авторизационный токен
	 * @return <ul>
	 *		<li>0 при успешном выполнении;</li>
	 *		<li>{@link Settings#SQL_ERROR}, если произошлёл сбой при закрытии;</li>
	 *		<li>{@link Settings#NO_SUCH_USER} если подан незарегистрированный или устаревший токен.</li>
	 * </ul>
	 */
	@WebMethod(operationName = "closeAllFetches")
	public int closeAllFetches(@WebParam(name = "token") String token) throws CarabiOracleError, CarabiException {
		logger.log(
			Level.FINE, 
			" closeAllFetches token={0}", token
			);

		try {
			UserLogon logon = usersController.tokenAuthorize(token);
			cursorFetcher.closeAllFetches(logon);
			return 0;
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw new CarabiOracleError(ex);
		} catch (RegisterException ex) {
			throw ex;
		} catch (CarabiException ex) {
			logger.log(Level.SEVERE, null, ex);
			throw ex;
		}
	}
	
	/**
	 * Запускает сохранённый SQL-запрос или PL-скрипт из служебной БД.
	 * Если запрос содержит входные или выходные параметры &ndash; их типы и направление
	 * задаются при инициализации методом {@link DeveloperService#putQuery(java.lang.String, java.lang.String, java.lang.String, boolean, java.util.ArrayList, java.lang.String, java.lang.Integer)},
	 * а значения задаются в параметре <code>parameters</code> в том же порядке,
	 * в котором они идут в тексте запроса. Если выходным параметром является курсор,
	 * то на выход передаётся JSON-объект с элементами columns (список полей), list (строки)
	 * и queryTag (номер сохранённой прокрутки, начиная с 0 или -1, если запрос закрыт).
	 * 
	 * Параметр sessionName используется, фактически, в качестве ключа кеширования
	 * (ранее предполагалось другое предназначение). Запросы от одного token-а с
	 * одинаковым именем, параметрами и ключом кеширования сохраняются и не
	 * запускаются повторно. Кеширование происходит только при непустом ключе и fetchAll = true
	 * @param token авторизационный токен
	 * @param sessionName Ключ кеша
	 * @param queryName имя хранимого запроса
	 * @param fetchCount если есть select или курсор &ndash; число возвращаемых записей.
	 * Если указано положительное количество (или 0) &ndash; курсор сохраняется в прокрутке,
	 * если отрицательное &ndash; закрывается.
	 * @param fetchAll вернуть все строки курсоров независимо от fetchCount
	 * @param parameters массив параметров.
	 * @return 0 при успешном выполнении
	 */
	@WebMethod(operationName = "runStoredQuery")
	public int runStoredQuery(
			@WebParam(name = "token") String token,
			@WebParam(name = "sessionName") String sessionName,
			@WebParam(name = "queryName") String queryName,
			@WebParam(name = "fetchCount") int fetchCount,
			@WebParam(name = "fetchAll") boolean fetchAll,
			@WebParam(name = "parameters", mode= WebParam.Mode.INOUT) Holder<ArrayList<QueryParameter>> parameters
		) throws CarabiException, CarabiOracleError, CarabiOracleMessage {
		final String parametersDump = Utls.dumpParameters(parameters.value);
		logger.log(
				Level.FINE, 
				"runStoredQuery token={0}, sessionName={1}, queryName={2}, fetchCount={3}, fetchAll={4} parameters={5}",
				new Object[] {token, sessionName, queryName, fetchCount, fetchAll, parametersDump}
			);

		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			logger.finest("Authorized!");
			if (fetchAll) {
				fetchCount = -Integer.MAX_VALUE;
			}
			Map<String, QueryParameter> parametersMap = new HashMap<>();
			for (QueryParameter parameter: parameters.value) {
				parametersMap.put(parameter.getName().toUpperCase(), parameter);
			}
			String[] cacheKey = new String[]{token, sessionName, queryName, parametersDump};
			ArrayList<QueryParameter> cached = cache.get(cacheKey);
			if (cached != null && fetchAll) {
				parameters.value = cached;
			} else {
				queryStorage.runQuery(logon, queryName, parametersMap, fetchCount);
				parameters.value = new ArrayList(parametersMap.values());
				wrapJson(parameters);
				if (fetchAll && !StringUtils.isEmpty(sessionName)) {
					cache.put(token, cacheKey, parameters.value);
				}
			}
			return 0;
		} catch (SQLException e) {
			String carabiMessage = Utls.filterCarabiBusinessLogic(e);
			if (carabiMessage != null) {
				logger.log(Level.WARNING, "Carabi business-logic error:", e);
				throw new CarabiOracleMessage(carabiMessage);
			} else {
				logger.log(Level.SEVERE, "Oracle error:", e);
				throw new CarabiOracleError(e);
			}
		} catch (CarabiException e) {
			logger.log(Level.WARNING, "Internal or logic eror:", e);
			throw e;
		} catch (Throwable e) {
			logger.log(Level.SEVERE, "Unknown error:", e);
			throw e;
		}
	}
	
	private void wrapJson(Holder<ArrayList<QueryParameter>> parameters) {
		for (QueryParameter queryParameter: parameters.value) {
			wrapJson(queryParameter);
		}
	}
	
	private void wrapJson(QueryParameter queryParameter) {
		if ("CURSOR".equals(queryParameter.getType())) {
			Map cursorData = (Map) queryParameter.getValueObject();
			JsonObjectBuilder cursorObject = Utls.mapToJson(cursorData);
			cursorObject.add("queryTag", queryParameter.getValue());
			queryParameter.setValue(cursorObject.build().toString());
			queryParameter.setValueObject(null);
		}
	}
}
