package ru.carabi.server.kernel.oracle;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.xml.ws.Holder;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;

/**
 * Модуль для работы с СУБД Oracle через SQL-запросы.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@Stateless
public class SqlQueryBean {
	
	private static final Logger logger = Logger.getLogger(SqlQueryBean.class.getName());
	
	@EJB
	private CursorFetcherBean cursorFetcher;
	
	/**
	 * Выполнение SQL-запроса. В зависимости от знака <code>fetchCount</code>
	 * &ndash; с открытием прокрутки.
	 * 
	 * @param logon
	 * @param sql
	 * @param startPos
	 * @param fetchCount
	 * @param queryTag
	 * @param columns
	 * @param list
	 * @param endpos
	 * @param lastTag
	 * @param count
	 * @return error code if sql fails, or count of records selected 
	 * if successful
	 */
	public int selectSql(
			UserLogon logon,
			String sql,
			ArrayList<QueryParameter> parameters,
			int startPos,
			int fetchCount,
			int queryTag,
			Holder<ArrayList<ArrayList<String>>> columns,
			Holder<ArrayList<ArrayList<?>>> list,
			Holder<Integer> endpos,
			Holder<Integer> lastTag,
			Holder<Integer> count
		) {
		try {
			int resultCode;
			endpos.value = startPos;
			lastTag.value = queryTag;
			//Смотрим, открыт ли у нас этот запрос
			Fetch fetch = cursorFetcher.searchOpenedFetch(logon, queryTag, startPos);
			boolean fetchIsNew = false;
			//если нет -- создаём
			if (fetch == null) {
				Connection connection = logon.getConnection();
				OracleConnection oracleConnection = Utls.unwrapOracleConnection(connection);
				OraclePreparedStatement statement = (OraclePreparedStatement)oracleConnection.prepareStatement(sql);
				OracleUtls.setInputParameters(statement, parameters);
				ResultSet cursor = statement.executeQuery();
				fetch = new Fetch(cursor, statement, startPos, logon.getConnectionKey(connection));
				fetchIsNew = true;
			}
			columns.value = fetch.columns;
			boolean askSaveFetch = fetchCount > 0;// по знаку fetchCount смотрим, надо ли сохранять прокрутку
			fetchCount = Math.abs(fetchCount);
			resultCode = fetchCount;
			ArrayList<ArrayList<?>> data = fetch.processFetching(fetchCount);
			count.value = data.size();
			endpos.value += count.value;
			//Если пользователь с долгоживущей сессией и новый
			//запрос содержит данные и подлежит сохранению -- сохраняем прокрутку
			if (logon.isRequireSession() && fetchIsNew && data.size() > 0 && askSaveFetch) {
				lastTag.value = cursorFetcher.saveFetch(fetch, logon);
			} else {
//				if (count.value == 0 && fetchCount != 0) {
//					resultCode = Settings.SQL_EOF;
//				}
				//закрываем, если не надо сохранять или кончились данные или пользователь не использует сессию
				if (!logon.isRequireSession() || !askSaveFetch || data.isEmpty() /*|| count.value < fetchCount*/ ) {
					lastTag.value = -1;
					if (fetchIsNew) {
						fetch.cursor.close();
						fetch.statement.close();
						logon.freeConnection(fetch.connectionKey);
					} else {
						cursorFetcher.closeFetch(logon, queryTag);
					}
				}
			}
			list.value = data;
			return resultCode;
		} catch (CarabiException e) {
			logger.log(Level.WARNING, null, e);
			return e.errorCode;
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
			return Settings.SQL_ERROR;
		} finally {
			if (!logon.isRequireSession()) {
				try {logon.closeAllConnections();}
				catch (SQLException ex) {
					logger.log(Level.SEVERE, null, ex);
				}
			}
		}
	}
	
	public int executeScript(UserLogon logon, String script, Holder<ArrayList<QueryParameter>> parameters, int fetchCount) {
		try {
			Connection connection = logon.getConnection();
			OracleConnection oracleConnection = Utls.unwrapOracleConnection(connection);
			script = OracleUtls.removeComments(script);
			ArrayList<String> parametersInSource = OracleUtls.searchInOut(script);
			if (parametersInSource.size() != parameters.value.size()) {
				throw new CarabiException("Number of parameters in script and input data is different", Settings.BINDING_ERROR);
			}
			script = OracleUtls.replaceInOut(script);
			int i = 0;
			OracleCallableStatement statement = (OracleCallableStatement)oracleConnection.prepareCall(script);
			for (QueryParameter parameter: parameters.value) {
				if (parameter.getIsIn() != null && parameter.getIsIn() > 0) {
					OracleUtls.setInputParameter(statement, parameter.getType(), parameter.getValue(), i + 1);
				}
				if (parameter.getIsOut() != null && parameter.getIsOut() > 0) {
					OracleUtls.registerOutputParameter(statement, parameter.getType(), i + 1);
				}
				i++;
			}
			statement.execute();
			i = 0;
			for (QueryParameter parameter: parameters.value) {
				if (parameter.getIsOut() != null && parameter.getIsOut() > 0) {
					OracleUtls.readOutputParameter(statement, parameter, i + 1);
				}
				i++;
			}
			boolean cursorsOpened = OracleUtls.fetchResultCursors(logon, parameters.value, fetchCount, connection, statement, cursorFetcher);
			if (!cursorsOpened) {
				statement.close();
				logon.freeConnection(connection);
			}
			return 0;
		} catch (CarabiException ex) {
			Logger.getLogger(SqlQueryBean.class.getName()).log(Level.SEVERE, null, ex);
			return ex.errorCode;
		} catch (SQLException ex) {
			Logger.getLogger(SqlQueryBean.class.getName()).log(Level.SEVERE, "error on executing: " + script);
			Logger.getLogger(SqlQueryBean.class.getName()).log(Level.SEVERE, "", ex);
			return Settings.SQL_ERROR;
		}
	}
	
	public QueryParameter getCarabiTable(UserLogon logon, String tableName, List<Long> documentsList, int fetchCount) {
		final String documentsListStr = StringUtils.join(documentsList, ", ");
		String sql = "begin :cur := APPL_CARABI_TABLE2.GET_CURSOR('" +
				tableName + "', T_NUMBER_LIST(" + documentsListStr + ")); end;";
		QueryParameter cur = new QueryParameter();
		cur.setName("cur");
		cur.setIsOut(1);
		cur.setType("CURSOR");
		ArrayList<QueryParameter> paramList = new ArrayList<QueryParameter>();
		paramList.add(cur);
		executeScript(logon, sql, new Holder<>(paramList), fetchCount);
		return cur;
	}
}
