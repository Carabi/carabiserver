package ru.carabi.server.kernel.oracle;

import ru.carabi.server.entities.QueryParameterEntity;
import ru.carabi.server.entities.QueryEntity;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleTypes;
import oracle.sql.NUMBER;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;

/**
 *
 * @author sasha
 */
public class OracleUtls {
	public static void setInputParameter(OraclePreparedStatement statement, QueryEntity queryEntity, QueryParameter inputParameter, QueryParameterEntity parameterEntity) throws SQLException, CarabiException {
		String type = parameterEntity.getType();
		if (inputParameter.getIsNull() > 0) {
			type = "NULL_" + type;
		}
		int ordernumber = parameterEntity.getOrdernumber();
		if (queryEntity.isSql()) {
			ordernumber++;
		}
		OracleUtls.setInputParameter(statement, type, inputParameter.getValue(), ordernumber);
	}
	
	public static void setInputParameter(OraclePreparedStatement statement, String typeName, String value, int ordernumber) throws SQLException, CarabiException {
		Logger.getLogger(OracleUtls.class.getName()).log(Level.FINE, "Input {0}: {1} = {2}", new Object[]{ordernumber, typeName, value});
		try {
			if (typeName != null && typeName.startsWith("NULL_")) {
				String type = typeName.substring(5);
				statement.setNull(ordernumber, typeIdByName(type));
			} else if ("VARCHAR2".equalsIgnoreCase(typeName) || "VARCHAR".equalsIgnoreCase(typeName) || "CHAR".equalsIgnoreCase(typeName)) {
				statement.setString(ordernumber, value);
			} else if ("NUMBER".equalsIgnoreCase(typeName)) {
				if (value == null || value.equals("") || value.equalsIgnoreCase("null")) {
					statement.setNull(ordernumber, typeIdByName("NUMBER"));
				} else {
					BigDecimal bd = new BigDecimal(value);
					statement.setNUMBER(ordernumber, new NUMBER(bd));
				}
			} else if ("DATE".equalsIgnoreCase(typeName)) {
				CarabiDate date = new CarabiDate(value);
				statement.setTimestamp(ordernumber, date);
			}
		} catch (IllegalArgumentException e) {
			throw new CarabiException(e, Settings.PARSING_ERROR);
		} catch (ParseException ex) {
			throw new CarabiException(ex, Settings.PARSING_ERROR);
		}
	}
	
	public static void setInputParameters(OraclePreparedStatement statement, ArrayList<QueryParameter> parametersInput) throws SQLException, CarabiException {
		for (QueryParameter parameter: parametersInput) {
			try {
				if (parameter.getIsNull() > 0) {
					statement.setNullAtName(parameter.getName(), typeIdByName(parameter.getType()));
				} else if ("VARCHAR2".equalsIgnoreCase(parameter.getType()) || "VARCHAR".equalsIgnoreCase(parameter.getType()) || "CHAR".equals(parameter.getType())) {
					statement.setStringAtName(parameter.getName(), parameter.getValue());
				} else if ("NUMBER".equalsIgnoreCase(parameter.getType())) {
					BigDecimal bd = new BigDecimal(parameter.getValue());
					statement.setNUMBERAtName(parameter.getName(), new NUMBER(bd));
				} else if ("DATE".equalsIgnoreCase(parameter.getType())) {
					CarabiDate date = new CarabiDate(parameter.getValue());
					statement.setTimestampAtName(parameter.getName(), date);
				}
			} catch (IllegalArgumentException e) {
				throw new CarabiException(e, Settings.PARSING_ERROR);
			} catch (ParseException ex) {
				throw new CarabiException(ex, Settings.PARSING_ERROR);
			}
		}
	}
	
	public static int typeIdByName(String typeName) throws CarabiException {
		if ("VARCHAR2".equalsIgnoreCase(typeName) || "VARCHAR".equalsIgnoreCase(typeName) || "CHAR".equalsIgnoreCase(typeName)) {
			return Types.VARCHAR;
		} else if ("NUMBER".equalsIgnoreCase(typeName)) {
			return OracleTypes.NUMBER;
		} else if ("DATE".equalsIgnoreCase(typeName)) {
			return Types.DATE;
		} else if ("REFCURSOR".equalsIgnoreCase(typeName) || "CURSOR".equalsIgnoreCase(typeName)) {
			return OracleTypes.CURSOR;
		} else {
			throw new CarabiException("Unknown type: " + typeName);
		}
	}
	
	public static void registerOutputParameter(OracleCallableStatement statement, QueryEntity queryEntity, QueryParameterEntity parameterEntity) throws CarabiException, SQLException {
		if (queryEntity.isSql()) {
			throw new CarabiException("SQL query can not have output parameters", Settings.BINDING_ERROR);
		}
		registerOutputParameter(statement, parameterEntity.getType(), parameterEntity.getOrdernumber());
	}
	
	public static void registerOutputParameter(OracleCallableStatement statement, String typeName, int ordernumber) throws CarabiException, SQLException {
		statement.registerOutParameter(ordernumber, typeIdByName(typeName));
	}
	public static void registerOutputParameters(OracleCallableStatement statement, ArrayList<QueryParameter> parametersOutput, ArrayList<String> parameters) throws CarabiException, SQLException {
		for (QueryParameter parameter: parametersOutput) {
			int position = parameters.indexOf(parameter.getName()) + 1;
			registerOutputParameter(statement, parameter.getType(), position);
		}
	}
	
	public static void readOutputParameter(OracleCallableStatement statement, QueryParameter parameter, int ordernumber) throws CarabiException {
		try {
			Object value = statement.getObject(ordernumber);
			parameter.setValueObject(value);
			if (value != null && String.class.equals(value.getClass())) {
				parameter.setType("VARCHAR2");
				parameter.setValue((String) value);
			} else if (value != null && java.math.BigDecimal.class.equals(value.getClass())) {
				parameter.setType("NUMBER");
				parameter.setValue(value.toString());
			} else if (value != null && java.sql.Timestamp.class.equals(value.getClass())) {
				parameter.setType("DATE");
				parameter.setValue(new CarabiDate((java.sql.Timestamp)value).toString());
			} else if (value != null && oracle.sql.TIMESTAMP.class.equals(value.getClass())) {
				parameter.setType("DATE");
				parameter.setValue(new CarabiDate(((oracle.sql.TIMESTAMP)value).timestampValue()).toString());
			} else if (value != null && java.sql.ResultSet.class.isAssignableFrom(value.getClass())){
				parameter.setType("CURSOR");
				parameter.setValue("CURSOR");
			} else if (value != null) {
				Logger.getLogger(OracleUtls.class.getName()).info(value.getClass().getName());
				parameter.setValue(value.toString());
			} else {
				parameter.setValue("");
			}
		} catch (SQLException ex) {
			throw new CarabiException(ex, Settings.SQL_ERROR);
		}
	}
	public static void getOutputParameters(OracleCallableStatement statement, ArrayList<QueryParameter> parametersOutput, ArrayList<String> parameters) throws CarabiException, SQLException {
		for (QueryParameter parameter: parametersOutput) {
			int ordernumber = parameters.indexOf(parameter.getName()) + 1;
			readOutputParameter(statement, parameter, ordernumber);
			parameter.setValueObject(statement.getObject(ordernumber));
		}
	}
	
	public static Pattern plVarSearcher = Pattern.compile("(:([a-zA-Z])([\\w\\#\\$]*))");
	/**
	 * Поиск входных и выходных параметров в PL-скрипте.
	 * В PL-скрипте вида 
	 * <code>
	 * declare
	 *   var type;
	 * begin
	 *   var := any_function(:input);
	 *   :output := another_function(var);
	 * end;
	 * </code>
	 * находит входные и выходные параметры (идентификаторы с двоеточиями)
	 * и возвращает в виде массива в порядке нахождения
	 * @param script PL-скрипт
	 * @return массив с параметрами
	 */
	public static ArrayList<String> searchInOut(String script) {
		Matcher matcher = plVarSearcher.matcher(script);
		ArrayList<String> parameters = new ArrayList<String>();
		while (matcher.find()) {
			parameters.add(matcher.group().substring(1));
		}
		return parameters;
	}
	
	/**
	 * Заменяет входные и выходные именованные параметры вопросиками.
	 */
	public static String replaceInOut(String script) {
		Matcher matcher = plVarSearcher.matcher(script);
		return matcher.replaceAll("?");
	}
	
	public static String removeComments(String script) {
		int l = script.length();
		if (l <= 1) {
			return script;
		}
		StringBuilder withoutComments = new StringBuilder(l);
		boolean lineOpened = false;
		boolean blockOpened = false;
		for (int i=0; i < l-1; i++) {
			if (lineOpened) {
				if (script.charAt(i) == '\n') {
					lineOpened = false;
					withoutComments.append('\n');
				}
				continue;
			}
			if (blockOpened) {
				if (script.charAt(i) == '*' && script.charAt(i+1) == '/') {
					i += 1;
					blockOpened = false;
				}
				continue;
			}
			if (script.charAt(i) == '-' && script.charAt(i + 1) == '-') {
				i += 1;
				lineOpened = true;
				continue;
			}
			if (script.charAt(i) == '/' && script.charAt(i + 1) == '*') {
				i += 1;
				blockOpened = true;
				continue;
			}
			if (! (lineOpened || blockOpened)) {
				withoutComments.append(script.charAt(i));
			}
		}
		if (! (lineOpened || blockOpened)) {
			withoutComments.append(script.charAt(l-1));
		}
		return withoutComments.toString();
	}
	
	/**
	 * Выполнение Oracle-запроса, содержащего чистый Select.
	 * Запись результата в объект {@link QueryParameter}.
	 */
	public static QueryParameter executeSql(OracleCallableStatement statement) throws CarabiException, SQLException {
		OracleUtls.registerOutputParameter(statement, "CURSOR", 1);
		statement.execute();
		QueryParameter resultСursor = new QueryParameter();
		OracleUtls.readOutputParameter(statement, resultСursor, 1);
		return resultСursor;
	}
	/**
	 * Получает данные из курсоров, имеющихся среди выходных параметров.
	 * В QueryParameter.valueObject записывает
	 * <code>
	 * Map<String, ArrayList<String, Object>>
	 * </code>
	 * , содержащую columns и list (или только columns, если Count, если fetchCount == 0)
	 * Если fetchCount положительный (или 0) &ndash; ResultSet сохраняется в прокрутке и её номер
	 * записывается в QueryParameter.value, если отрицательный &ndash; закрывается.
	 * 
	 * @return были ли сохранены прокрутки с курсорами
	 * @throws SQLException при ошибках взаимодействия с Oracle
	 * @throws CarabiException если пользователь сохранил более {@link Settings#FETCHES_BY_USER} прокруток
	 */
	public static boolean fetchResultCursors(UserLogon logon, Collection<QueryParameter> parameters, int fetchCount, Statement statement, CursorFetcherBean cursorFetcher) throws SQLException, CarabiException {
		boolean cursorsSaved = false;
		boolean saveCursors = logon.isRequireSession() && fetchCount >= 0;
		fetchCount = Math.abs(fetchCount);
		for (QueryParameter parameter: parameters) {
			if (parameter.getValueObject() != null && "CURSOR".equals(parameter.getType()) && ResultSet.class.isAssignableFrom(parameter.getValueObject().getClass())) {
				ResultSet cursor = (ResultSet) parameter.getValueObject();
				Map<String, ArrayList> result = new HashMap<>();
				parameter.setValueObject(result);
				parameter.setValue("-1");
				ArrayList<ArrayList<String>> columns = Utls.getResultSetColumns(cursor);
				result.put("columns", columns);
				Fetch fetch = new Fetch(cursor, statement, 0);
				ArrayList<ArrayList<?>> list = fetch.processFetching(fetchCount);
				result.put("list", list);
				if (saveCursors && list.size() == fetchCount) {
					parameter.setValue(String.valueOf(cursorFetcher.saveFetch(fetch, logon)));
					cursorsSaved = true;
				} else {
					cursor.close();
				}
			}
		}
		return cursorsSaved;
	}
}
