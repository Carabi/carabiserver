package ru.carabi.server.kernel.oracle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.entities.QueryParameterEntity;
import ru.carabi.server.entities.QueryEntity;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Запуск именованных запросов.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@Stateless
public class QueryStorageBean {
	static final Logger logger = Logger.getLogger(QueryStorageBean.class.getName());
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager em;

	@EJB
	private CursorFetcherBean cursorFetcher;

	
	/**
	 * Запускает сохранённый запрос или PL-код, подставляя параметры по порядку.
	 * Входные параметры должны содержать значение, выходные могут быть пустыми объектами.
	 * Тип берётся из БД. Параметры должны идти в том же порядке, в котором они встречаются в запросе.
	 * @param logon текущий пользователь.
	 * @param name название запроса.
	 * @param parameters параметры запроса по порядку.
	 * @param fetchCount шаг прокрутки выходного курсора
	 * @throws CarabiException если заданы не все входные параметры, есть неизвестный параметр или не найден запрос
	 * @throws SQLException 
	 */
//	 * Рекомендуется. Улучшить быстродействие этого метода. 
//	 * 
//	 * 1) Запрашивать запрос из кэша:
//	 *       - если есть, шаг 2,
//	 *       - если нет, находить в бд - и добавлять в кэш, если нет в бд - исключение.
//	 * 2) Вернуть результат. 
//	 */
	public void runQuery(UserLogon logon,
			String name,
			List<QueryParameter> parameters,
			int fetchCount
		) throws CarabiException, SQLException {
		QueryEntity queryEntity = prepareNamedQuery(name, logon);
		if (parameters == null) {
			parameters = new ArrayList<>();
		}
		List<QueryParameterEntity> parametersEntities = queryEntity.getParameters();
		if (parametersEntities.size() != parameters.size()) {
			throw new CarabiException("Number of stored parameters and number of input parameters are different", Settings.BINDING_ERROR);
		}
		final String parametersDump = Utls.dumpParameters(parameters);
		try {
			logQueryEnter(logon, queryEntity, parametersDump);
			Connection connection = logon.getConnection();
			OracleConnection oracleConnection = Utls.unwrapOracleConnection(connection);
			OracleCallableStatement statement = prepareStoredQuery(queryEntity, oracleConnection);
			int i = 0;
			for (QueryParameterEntity parameterEntity: parametersEntities) {
				if (parameterEntity.getIsIn() > 0) {
					QueryParameter inputParameter = parameters.get(i);
					if (inputParameter == null) {
						throw new CarabiException("Input parameter " + i + " not given");
					}
					OracleUtls.setInputParameter(statement, queryEntity, inputParameter, parameterEntity);
				}
				if (parameterEntity.getIsOut() > 0) {
					OracleUtls.registerOutputParameter(statement, queryEntity, parameterEntity);
				}
				i++;
			}
			if (queryEntity.isSql()) {
				parameters.add(OracleUtls.executeSql(statement));
			} else {
				statement.execute();
				i = 0;
				for (QueryParameterEntity parameterEntity: parametersEntities) {
					if (parameterEntity.getIsOut() > 0) {
						OracleUtls.readOutputParameter(statement, parameters.get(i), parameterEntity.getOrdernumber());
					}
					i++;
				}
			}
			fetchResultAndLog(logon, parameters, fetchCount, connection, statement, name);
		} catch(SQLException e) {
			CarabiLogging.logError("Ошибка при выполнении запроса {0} с параметрами {1}",
					new Object[]{name, parametersDump},
					logon.getMasterConnection(), true, Level.SEVERE, e);
			throw e;
		}
	}
	
	/**
	 * Запускает сохранённый запрос или PL-код, подставляя параметры по имени.
	 * Для входных параметров должны быть заданы имя и значение, тип берётся из БД.
	 * Выходные параметры добавляются автоматически.
	 * @param logon текущий пользователь.
	 * @param name название запроса.
	 * @param parameters параметры запроса по именам.
	 * @param fetchCount шаг прокрутки выходного курсора
	 * @throws CarabiException если заданы не все входные параметры, есть неизвестный параметр или не найден запрос
	 * @throws SQLException 
	 */
	public void runQuery(UserLogon logon,
			String name,
			Map<String, QueryParameter> parameters,
			int fetchCount
		) throws CarabiException, SQLException {
		QueryEntity queryEntity = prepareNamedQuery(name, logon);
		if (parameters == null) {
			parameters = new HashMap<>();
		}
		List<QueryParameterEntity> parametersEntities = queryEntity.getParameters();
		final String parametersDump = Utls.dumpParameters(parameters);
		try {
			logQueryEnter(logon, queryEntity, parametersDump);
			Connection connection = logon.getConnection();
			OracleConnection oracleConnection = Utls.unwrapOracleConnection(connection);
			OracleCallableStatement statement = prepareStoredQuery(queryEntity, oracleConnection);
			for (QueryParameterEntity parameterEntity: parametersEntities) {
				if (parameterEntity.getIsIn() > 0) {
					QueryParameter inputParameter = parameters.get(parameterEntity.getName().toUpperCase());
					if (inputParameter == null) {
						throw new CarabiException("Input parameter " + parameterEntity.getName() + " not given");
					}
					OracleUtls.setInputParameter(statement, queryEntity, inputParameter, parameterEntity);
				}
				if (parameterEntity.getIsOut() > 0) {
					OracleUtls.registerOutputParameter(statement, queryEntity, parameterEntity);
				}
			}
			parameters.clear();
			if (queryEntity.isSql()) {
				parameters.put("RESULT_CURSOR", OracleUtls.executeSql(statement));
			} else {
				statement.execute();
				for (QueryParameterEntity parameterEntity: parametersEntities) {
					if (parameterEntity.getIsOut() > 0) {
						QueryParameter outParameter = new QueryParameter();
						outParameter.setName(parameterEntity.getName());
						OracleUtls.readOutputParameter(statement, outParameter, parameterEntity.getOrdernumber());
						parameters.put(parameterEntity.getName(), outParameter);
					}
				}
			}
			fetchResultAndLog(logon, parameters.values(), fetchCount, connection, statement, name);
		} catch(SQLException e) {
			CarabiLogging.logError("Ошибка Oracle при выполнении запроса {0} с параметрами {1}",
					new Object[]{name, parametersDump},
					logon.getMasterConnection(), true, Level.SEVERE, e);
			throw e;
		} catch(CarabiException e) {
			CarabiLogging.logError("Внутренняя ошибка при выполнении запроса {0} с параметрами {1}",
					new Object[]{name, parametersDump},
					logon.getMasterConnection(), true, Level.SEVERE, e);
			throw e;
		}
	}
	
	private void logQueryEnter(UserLogon logon, QueryEntity queryEntity, final String parametersDump) {
		CarabiLogging.log(logon,
				this,
				String.format(CarabiLogging.messages.getString("executingTheQuery"), queryEntity.getSysname(), queryEntity.getName()),
				parametersDump
		);
		if (queryEntity.getIsDeprecated()) {
			CarabiLogging.log(logon,
					this,
					String.format(CarabiLogging.messages.getString("executingDepreatedQuery"), queryEntity.getName()),
					String.format(CarabiLogging.messages.getString("executingDepreatedQueryDetails"), queryEntity.getSysname(), logon.getDisplay(), logon.getToken())
			);
		}
	}
	
	/**
	 * Подготовка хранимого запроса.
	 * Поиск и проверка, что он может работать.
	 * @param name имя хранимого запроса
	 * @param logon текущий пользователь.
	 * @throws CarabiException если запрос не найден или не работает с текущей базой (при {@link Settings#CHECK_STORED_QUERY_BASE} true)
	 */
	private QueryEntity prepareNamedQuery(
			String name,
			UserLogon logon
			) throws CarabiException {
		QueryEntity queryEntity = findNamedQuery(name);
		if (queryEntity == null) {
			throw new CarabiException("Named query " + name + " was not found");
		}
		if (Settings.CHECK_STORED_QUERY_BASE && queryEntity.getSchema() != null && !queryEntity.getSchema().equals(logon.getSchema())) {
			throw new CarabiException("Stored query " + name + " do not work with database " + logon.getSchema().getName(), Settings.BINDING_ERROR);
		}
		return queryEntity;
	}
	
	/**
	 * Подготовка к запуску хранимого запроса от данного пользователя.
	 * @param queryEntity данные о запросе
	 * @param user пользовательская сессия
	 * @return скомпилированный запрос
	 */
	private OracleCallableStatement prepareStoredQuery(QueryEntity queryEntity, OracleConnection connection) throws SQLException, CarabiException {
		String query = queryEntity.getBody();
		if (queryEntity.isSql()) {
			query = "begin open :RESULT_CURSOR for " + query + "; end;";
		}
		return (OracleCallableStatement)connection.prepareCall(query);
	}
	private void fetchResultAndLog(UserLogon logon, Collection<QueryParameter> parameters, int fetchCount, Connection connection, OracleCallableStatement statement, String queryName) throws SQLException, CarabiException {
			boolean cursorsOpened = OracleUtls.fetchResultCursors(logon, parameters, fetchCount, connection, statement, cursorFetcher);
			String message;
			if (!cursorsOpened) {
				statement.close();
				message = "statementClosed";
			} else {
				message = "cursorsOpened";
			}
			CarabiLogging.log(logon, this, String.format(CarabiLogging.messages.getString("queryHasBeenExecuted"), queryName),
					CarabiLogging.messages.getString(message));
	}
	
	/**
	 * Поиск хранимого запроса в ядровой базе
	 * @param queryName
	 * @return 
	 */
	private QueryEntity findNamedQuery(String queryName) {
		Query jpaQuery = em.createNamedQuery("findNamedQuery");
		jpaQuery.setParameter("queryName", queryName);
		List resultList = jpaQuery.getResultList();
		if (resultList.size() > 0) {
			return em.find(QueryEntity.class, resultList.get(0));
		} else {
			return null;
		}
	}

	/**
	 * Запуск хранимого select-а без параметров.
	 * @param logon текущий пользователь
	 * @param name имя запроса
	 * @param fetchCount шаг прокрутки выходного курсора
	 * @return Массив строк
	 * @throws ru.carabi.server.CarabiException Запрос не найден
	 * @throws java.sql.SQLException Ошибка при выполнении
	 */
	public ArrayList<LinkedHashMap<String, ?>> runOutonlyQuery(UserLogon logon, String name, int fetchCount) throws CarabiException, SQLException{
		ArrayList<QueryParameter> parameters = new ArrayList<>();
		runQuery(logon, name, parameters, fetchCount);
		Map<String, ArrayList<ArrayList<?>>> result = parameters.get(0).getCursorValueRaw();
		return Utls.redim(result);
	}

	/**
	 * Запуск хранимого select-а с одним параметром.
	 * @param logon текущий пользователь
	 * @param name имя запроса
	 * @param value значение параметра
	 * @param fetchCount шаг прокрутки выходного курсора
	 * @return Массив строк
	 */
	public ArrayList<LinkedHashMap<String, ?>> runSmallQuery(UserLogon logon, String name, String value, int fetchCount) throws CarabiException, SQLException{
		ArrayList<QueryParameter> parameters = new ArrayList<>(2);
		QueryParameter documentIdParam = new QueryParameter();
		documentIdParam.setValue(value);
		parameters.add(documentIdParam);
		runQuery(logon, name, parameters, fetchCount);
		Map<String, ArrayList<ArrayList<?>>> result = parameters.get(1).getCursorValueRaw();
		return Utls.redim(result);
	}

	/**
	 * Запуск хранимого select-а с одним параметром, заведомо
	 * возвращающего одну строку с одним полем.
	 * @param logon текущий пользователь
	 * @param name имя запроса
	 * @param value значение единственного параметра
	 * @return Значение из первого поля первой строки
	 */
	public Object runSimpleQuery(UserLogon logon, String name, String value) throws CarabiException, SQLException {
		ArrayList<QueryParameter> parameters = new ArrayList<>(2);
		QueryParameter documentIdParam = new QueryParameter();
		documentIdParam.setValue(value);
		parameters.add(documentIdParam);
		runQuery(logon, name, parameters, -1);
		Map<String, ArrayList<ArrayList<?>>> result = parameters.get(1).getCursorValueRaw();
		return result.get("list").get(0).get(0);
	}
}
