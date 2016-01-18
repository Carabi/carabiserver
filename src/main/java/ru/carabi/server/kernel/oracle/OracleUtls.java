package ru.carabi.server.kernel.oracle;

import ru.carabi.server.entities.QueryParameterEntity;
import ru.carabi.server.entities.QueryEntity;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
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
	
	/**
	 * Конвертирование условных наименований SQL-типов, используемых в Carabi Server, в JDBC-номера.
	 * Используемые наименования:
	 * <ul>
	 * <li>CHAR, VARCHAR или VARCHAR2 &mdash; строковые (поведение эквивалентно)
	 * <li>NUMBER &mdash; числовое
	 * <li>DATE &mdash; дата
	 * <li>CLOB &mdash; объёмный текст.
	 * <ul>
	 * <li>CLOB_AS_VARCHAR &mdash; при считывании помечается, как строка.
	 * <li>CLOB_AS_CURSOR &mdash; при считывании помечается, как курсор (текст должен содержать соответствующий JSON для принятия клиентом). Запись игнорируется.
	 * <li>CURSOR или REFCURSOR &mdash; курсор, считывается в виде ResultSet и сериализуется в JSON с выделенной шапкой.
	 * </ul>
	 * </ul>
	 * @param typeName Название типа
	 * @return номер типа по стандарту JDBC
	 * @throws CarabiException неизвестное название
	 */
	public static int typeIdByName(String typeName) throws CarabiException {
		if ("VARCHAR2".equalsIgnoreCase(typeName) || "VARCHAR".equalsIgnoreCase(typeName) || "CHAR".equalsIgnoreCase(typeName)) {
			return Types.VARCHAR;
		} else if ("NUMBER".equalsIgnoreCase(typeName)) {
			return Types.NUMERIC;
		} else if ("DATE".equalsIgnoreCase(typeName)) {
			return Types.DATE;
		} else if ("CLOB".equalsIgnoreCase(typeName) || "CLOB_AS_VARCHAR".equalsIgnoreCase(typeName) || "CLOB_AS_CURSOR".equalsIgnoreCase(typeName)) {
			return Types.CLOB;
		} else if ("REFCURSOR".equalsIgnoreCase(typeName) || "CURSOR".equalsIgnoreCase(typeName)) {
			return OracleTypes.CURSOR;
		} else {
			throw new CarabiException("Unknown type: " + typeName);
		}
	}
	
	/**
	 * Установка входящего параметра в SQL-выражении.
	 * @param statement выполняемое выражение
	 * @param typeName название типа из списка в описании {@link #typeIdByName(java.lang.String)} (для NULL-значений -- с префиксом "NULL_")
	 * @param value строковое представление значения
	 * @param ordernumber номер параметра в выражении, начиная с 1
	 * @throws SQLException
	 * @throws CarabiException неизвестный тип или неверное значение
	 */
	public static void setInputParameter(OraclePreparedStatement statement, String typeName, String value, int ordernumber) throws SQLException, CarabiException {
		Logger.getLogger(OracleUtls.class.getName()).log(Level.FINE, "Input {0}: {1} = {2}", new Object[]{ordernumber, typeName, value});
		try {
			if (typeName != null && typeName.startsWith("NULL_")) {
				String type = typeName.substring(5);
				statement.setNull(ordernumber, typeIdByName(type));
			} else if ("VARCHAR2".equalsIgnoreCase(typeName) || "VARCHAR".equalsIgnoreCase(typeName) || "CHAR".equalsIgnoreCase(typeName)) {
				statement.setString(ordernumber, value);
			} else if ("NUMBER".equalsIgnoreCase(typeName)) {
				if (value == null || value.trim().equals("") || value.equalsIgnoreCase("null")) {
					statement.setNull(ordernumber, typeIdByName("NUMBER"));
				} else {
					value = value.replaceAll(" ", "");
					value = value.replaceFirst("\\,", ".");
					BigDecimal bd = new BigDecimal(value);
					statement.setNUMBER(ordernumber, new NUMBER(bd));
				}
			} else if ("DATE".equalsIgnoreCase(typeName)) {
				CarabiDate date = new CarabiDate(value);
				statement.setTimestamp(ordernumber, date);
			} else if ("CLOB".equalsIgnoreCase(typeName) || "CLOB_AS_VARCHAR".equalsIgnoreCase(typeName)) {
				Clob clob = statement.getConnection().createClob();
				clob.setString(1, value);
				statement.setClob(ordernumber, clob);
			} else {
				Logger.getLogger(OracleUtls.class.getName()).warning(value.getClass().getName());
			}
		} catch (IllegalArgumentException | ParseException e) {
			throw new CarabiException(e, Settings.PARSING_ERROR);
		}
	}
	
	/**
	 * Вызов {@link #setInputParameter(oracle.jdbc.OraclePreparedStatement, java.lang.String, java.lang.String, int)}
	 * для массива параметров.
	 * @param statement выполняемое выражение
	 * @param parametersInput массив параметров
	 * @throws SQLException
	 * @throws CarabiException неизвестный тип или неверное значение
	 */
	public static void setInputParameters(OraclePreparedStatement statement, ArrayList<QueryParameter> parametersInput) throws SQLException, CarabiException {
		int i = 1;
		for (QueryParameter inputParameter: parametersInput) {
			String type = inputParameter.getType();
			if (inputParameter.getIsNull() > 0) {
				type = "NULL_" + type;
			}
			setInputParameter(statement, type, inputParameter.getValue(), i);
			i++;
		}
	}
	
	/**
	 * Вызов {@link #setInputParameter(oracle.jdbc.OraclePreparedStatement, java.lang.String, java.lang.String, int)}
	 * для хранимых запросов.
	 * 
	 * @param statement выполняемое выражение
	 * @param queryEntity данные о запросе из ядровой БД
	 * @param inputParameter данные о параметре от клиента
	 * @param parameterEntity данные о параметре из ядровой БД
	 * @throws java.sql.SQLException
	 * @throws ru.carabi.server.CarabiException
	 */
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
		QueryParameterEntity parameterEntity = new QueryParameterEntity();
		parameterEntity.setOrdernumber(ordernumber);
		readOutputParameter(statement, parameter, parameterEntity);
	}
	
	/**
	 * Получение выходного параметра из запроса к БД.
	 * @param statement запрос к БД
	 * @param parameter объект
	 * @param parameterEntity
	 * @throws CarabiException 
	 */
	public static void readOutputParameter(OracleCallableStatement statement, QueryParameter parameter, QueryParameterEntity parameterEntity) throws CarabiException {
		parameter.setName(parameterEntity.getName());
		int ordernumber = parameterEntity.getOrdernumber();
		try {
			Object value = statement.getObject(ordernumber);
			parameter.setValueObject(value);
			if (value == null) {
				parameter.setIsNull(1);
				parameter.setValue("");//some clients crash when object does not contain any value
			} else if (String.class.isInstance(value)) {
				parameter.setType("VARCHAR2");
				parameter.setValue((String) value);
			} else if (java.math.BigDecimal.class.isInstance(value)) {
				parameter.setType("NUMBER");
				parameter.setValue(value.toString());
			} else if (java.util.Date.class.isInstance(value)) {
				parameter.setType("DATE");
				parameter.setValue(new CarabiDate((java.util.Date)value).toString());
			} else if (oracle.sql.TIMESTAMP.class.isInstance(value)) {
				parameter.setType("DATE");
				parameter.setValue(new CarabiDate(((oracle.sql.TIMESTAMP)value).timestampValue()).toString());
			} else if (oracle.sql.CLOB.class.isInstance(value)) {
				parameter.setType("CLOB");
				parameter.setValue(parameterEntity.getType().toUpperCase());// CLOB or CLOB_AS_VARCHAR or CLOB_AS_CURSOR
			} else if (java.sql.ResultSet.class.isInstance(value)){
				parameter.setType("CURSOR");
				parameter.setValue("CURSOR");
			} else {
				Logger.getLogger(OracleUtls.class.getName()).info(value.getClass().getName());
				parameter.setValue(value.toString());
			}
		} catch (SQLException ex) {
			throw new CarabiException(ex, Settings.SQL_ERROR);
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
	public static boolean fetchResultCursors(UserLogon logon, Collection<QueryParameter> parameters, int fetchCount, Connection connection, Statement statement, CursorFetcherBean cursorFetcher) throws SQLException, CarabiException {
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
				Fetch fetch = new Fetch(cursor, statement, 0, logon.getConnectionKey(connection));
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
