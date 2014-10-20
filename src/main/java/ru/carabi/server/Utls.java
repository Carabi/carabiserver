package ru.carabi.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Wrapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.enterprise.context.Dependent;
import javax.inject.Named;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import oracle.jdbc.OracleConnection;
import ru.carabi.server.kernel.oracle.CarabiDate;
import ru.carabi.server.kernel.oracle.QueryParameter;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Полезные методы для применения откуда угодно
 * @author sasha<kopilov.ad@gmail.com>
 */
@Named(value = "utls")
@Dependent
public class Utls {

	public static String dumpParameters(List<QueryParameter> parameters) {
		StringBuilder result = new StringBuilder();
		result.append("[");
		for (QueryParameter parameter: parameters) {
			result.append(parameter.getValue())
			      .append(", ");
		}
		result.append("]\n");
		return result.toString();
	}
	
	public static String dumpParameters(Map<String, QueryParameter> parameters) {
		StringBuilder result = new StringBuilder();
		result.append("{");
		for (String paramName: parameters.keySet()) {
			result.append(paramName)
			      .append(": ")
			      .append(parameters.get(paramName).getValue())
			      .append(",\n");
		}
		result.append("}");
		return result.toString();
	}
	
	/**
	 *
	 * @param main
	 * @param def
	 */
	public static String showNotEmpty(String main, String def) {
		if (main == null || main.equals("")) {
			return def;
		}
		return main;
	}

	/**
	 * Выборка всех записей со всеми полями из SQL-запроса
	 * @param resultSet Выполненный SQL-запрос
	 * @return Таблица в виде списока ассоциативных массивов
	 * @throws SQLException 
	 */
	public static ArrayList<LinkedHashMap<String, ?>> fetchAll(ResultSet resultSet) throws SQLException {
		ArrayList<LinkedHashMap<String, ?>> result = new ArrayList<>();
		int colN = resultSet.getMetaData().getColumnCount();
		ArrayList<String> columns = new ArrayList<>(colN);
		for (int i=1; i<=colN; i++) {
			columns.add(resultSet.getMetaData().getColumnName(i));
		}
		while (resultSet.next()) {
			LinkedHashMap<String, ?> row = Utls.fetchRow(resultSet, columns);
			result.add(row);
		}
		return result;
	}
	
	/**
	 * Выборка набора полей из текущей записи в SQL-выборке.
	 * @param resultSet
	 * @param columnsNames
	 * @return Строка в виде карты Имя-Значение
	 * @throws SQLException 
	 */
	public static LinkedHashMap<String, ?> fetchRow(ResultSet resultSet, ArrayList<String> columnsNames) throws SQLException {
		int colN = columnsNames.size();
		LinkedHashMap<String, Object> row = new LinkedHashMap<>(colN);
		for (int i=1; i<=colN; i++) {
			Object object = resultSet.getObject(i);
			if (oracle.sql.CLOB.class.isInstance(object)) {
				oracle.sql.CLOB clob = (oracle.sql.CLOB) object;
				row.put(columnsNames.get(i-1), clob.stringValue());
			} else if (java.sql.Timestamp.class.isInstance(object) || oracle.sql.TIMESTAMP.class.isInstance(object)) {
				CarabiDate carabiDate = new CarabiDate((java.sql.Timestamp)object);
				row.put(columnsNames.get(i-1), carabiDate.toString());
			} else {
				row.put(columnsNames.get(i-1), object);
			}
		}
		return row;
	}
	/**
	 * Выборка всех полей из текущей записи в SQL-выборке.
	 * @param resultSet SQL-выборка
	 * @return Строка в виде карты Имя-Значение
	 * @throws SQLException 
	 */
	public static LinkedHashMap<String, ?> fetchRow(ResultSet resultSet) throws SQLException {
		ArrayList<String> columnsNames = getResultSetColumnsNames(resultSet);
		return fetchRow(resultSet, columnsNames);
	}
	
	/**
	 * Получение списка названий столбцов из SQL-выборки.
	 * @param resultSet SQL-выборка
	 * @return Массив названий столбцов
	 */
	public static ArrayList<String> getResultSetColumnsNames(ResultSet resultSet) throws SQLException {
		int colN = resultSet.getMetaData().getColumnCount();
		ArrayList<String> columns = new ArrayList<>(colN);
		for (int i=1; i<=colN; i++) {
			columns.add(resultSet.getMetaData().getColumnName(i));
		}
		return columns;
	}
	
	/**
	 * Получение шапки из SQL-выборки.
	 * @param resultSet SQL-выборка
	 * @return Массив названий и типов столбцов
	 */
	public static ArrayList<ArrayList<String>> getResultSetColumns(ResultSet resultSet) throws SQLException {
		int colN = resultSet.getMetaData().getColumnCount();
		ArrayList<ArrayList<String>> columns = new ArrayList<>(colN);
		for (int i=1; i<=colN; i++) {
			ArrayList<String> column = new ArrayList<>(2);
			ResultSetMetaData metaData = resultSet.getMetaData();
			column.add(metaData.getColumnName(i));
			column.add(metaData.getColumnTypeName(i));
			columns.add(column);
		}
		return columns;
	}
	
	public static OracleConnection unwrapOracleConnection(Wrapper jdbcWrapper) throws SQLException {
		if (jdbcWrapper.isWrapperFor(OracleConnection.class)) {
			return jdbcWrapper.unwrap(OracleConnection.class);
		} else {
			throw new SQLException("JDBC Wrapper does not contain OracleConnection");
		}
	}
	
	public static int getStatusID(Connection connection, String statusName) throws SQLException {
		CallableStatement statement = connection.prepareCall("begin :STATUS_ID := GET_DOCEVENTKIND_ID(:STATUS_NAME); end;");
		statement.registerOutParameter("STATUS_ID", Types.INTEGER);
		statement.setString("STATUS_NAME", statusName);
		statement.execute();
		int statusID  = statement.getInt("STATUS_ID");
		statement.close();
		return statusID;
	}
	
	/**
	 * Перевод выборки из формата "шапка и матрица значений" в формат "массив ассоциативных массивов".
	 * @param input входные данные -- карта с полями columns и list, содержащими шапку и значения.
	 * @return 
	 */
	public static ArrayList<LinkedHashMap<String, ?>> redim(Map<String, ArrayList<ArrayList<?>>> input) {
		return redim(input.get("columns"), input.get("list"));
	}
	
	/**
	 * Перевод выборки из формата "шапка и матрица значений" в формат "массив ассоциативных массивов".
	 * @param columns шапка -- массив названий и форматов колонок
	 * @param list значения -- массив строк с полями
	 * @return 
	 */
	public static ArrayList<LinkedHashMap<String, ?>> redim(ArrayList<ArrayList<?>> columns, ArrayList<ArrayList<?>> list) {
		ArrayList<LinkedHashMap<String, ?>> result = new ArrayList<>(list.size());
		for (ArrayList<?> rowInput: list) {
			LinkedHashMap<String, Object> rowOutput = new LinkedHashMap<>();
			int i = 0;
			for (ArrayList<?> column: columns) {
				rowOutput.put((String)column.get(0), rowInput.get(i));
				i++;
			}
			result.add(rowOutput);
		}
		return result;
	}
	
	/**
	 * Перевод выборки из формата "шапка и матрица значений" в формат "массив ассоциативных объектов".
	 * @param table объект с полями columns (шапка -- массив названий и форматов колонок) и list (значения -- массив строк с полями) 
	 * @return массив ассоциативных объектов
	 */
	public static JsonArray redim(JsonObject table) {
		JsonArray columns = table.getJsonArray("columns");
		JsonArray list = table.getJsonArray("list");
		JsonArrayBuilder result = Json.createArrayBuilder();
		ArrayList<String> columnsNames = new ArrayList<>(columns.size());
		for (int i=0; i< columns.size(); i++) {
			JsonArray column = columns.getJsonArray(i);
			columnsNames.add(column.getString(0));
		}
		
		for (int i=0; i< list.size(); i++) {
			JsonArray row = list.getJsonArray(i);
			JsonObjectBuilder rowOutput = Json.createObjectBuilder();
			for (int j=0; j< row.size(); j++) {
				rowOutput.add(columnsNames.get(j), row.get(j));
			}
			result.add(rowOutput);
		}
		return result.build();
	}
	
	public static JsonObjectBuilder mapToJson(Map<String, ?> mapObject, String[] putToList) {
		JsonObjectBuilder jsonObject = Json.createObjectBuilder();
		for (String key: mapObject.keySet()) {
			if (putToList != null && putToList.length > 0 && Arrays.binarySearch(putToList, key) < 0) {
				continue;
			}
			if (mapObject.get(key) == null) {
				jsonObject.addNull(key);
			} else {
				jsonObject.add(key, mapObject.get(key).toString());
			}
		}
		return jsonObject;
	}
	
	public static JsonObjectBuilder mapToJson(Map<String, ?> mapObject) {
		return mapToJson(mapObject, null);
	}
	
	public static JsonArrayBuilder tableToJson(List<LinkedHashMap<String, ?>> table) {
		JsonArrayBuilder jsonArray = Json.createArrayBuilder();
		for (LinkedHashMap<String, ?> row: table) {
			jsonArray.add(mapToJson(row));
		}
		return jsonArray;
	}
	
	/**
	 * Создание JsonArrayBuilder из неограниченного числа параметров
	 * @param params
	 * @return 
	 */
	public static JsonArrayBuilder parametersToJson(String... params) {
		JsonArrayBuilder jsonArray = Json.createArrayBuilder();
		for (String param: params) {
			jsonArray.add(param);
		}
		return jsonArray;
	}
	
	public static void addJsonObject(JsonObjectBuilder jsonObject, String name, Object value) {
		if (value == null) {
			jsonObject.addNull(name);
		} else {
			jsonObject.add(name, value.toString());
		}
	}
	
	public static void addJsonObject(JsonArrayBuilder jsonArray, Object value) {
		if (value == null) {
			jsonArray.addNull();
		} else {
			jsonArray.add(value.toString());
		}
	}
	
	public static void addJsonNumber(JsonObjectBuilder jsonObject, String name, Number value) {
		if (value == null) {
			jsonObject.addNull(name);
		} else {
			jsonObject.add(name, value.toString());
		}
	}
	
	public static void addJsonNumber(JsonArrayBuilder jsonArray, Number value) {
		if (value == null) {
			jsonArray.addNull();
		} else {
			jsonArray.add(value.longValue());
		}
	}
	
	/**
	 * Извлечение массива ASCII символов из строки, перевод
	 * в массива байтов и строку в кодировке UTF-8.
	 * @param byteArray массив байтов, завёрнутый в строку
	 * @return готовая строка
	 */
	public static String decodeStringAsByteArray(String byteArray) {
		return decodeStringAsByteArray(byteArray, Charset.forName("UTF-8"));
	}
	
	/**
	 * Извлечение массива ASCII символов из строки, перевод
	 * в массива байтов и строку в указанной кодировке.
	 * @param byteArray массив байтов, завёрнутый в строку
	 * @param charset требуемая кодировка
	 * @return готовая строка
	 */
	public static String decodeStringAsByteArray(String byteArray, Charset charset) {
		byte[] realBytes = new byte[byteArray.length()];
		for (int i=0; i<byteArray.length(); i++) {
			realBytes[i] = (byte) byteArray.charAt(i);
		}
		return new String(realBytes, charset);
	}
	/**
	 * Представление байтов строки (в кодировке UTF-8) в виде символов внутри
	 * другой строки
	 * @param inputString исходная строка
	 * @return строка "символов", содержащих отдельные байты
	 */
	public static String encodeStringAsByteArray(String inputString) {
		return encodeStringAsByteArray(inputString, Charset.forName("UTF-8"));
	}
	
	/**
	 * Извлечение массива ASCII символов из строки, перевод
	 * в массива байтов и строку в указанной кодировке.
	 * @param inputString исходная строка
	 * @param charset требуемая кодировка
	 * @return строка "символов", содержащих отдельные байты
	 */
	public static String encodeStringAsByteArray(String inputString, Charset charset) {
		byte[] inputBytes = inputString.getBytes(charset);
		char[] charArray = new char[inputBytes.length];
		for (int i=0; i<inputBytes.length; i++) {
			charArray[i] = (char) inputBytes[i];
		}
		return new String(charArray);
	}
	
	/**
	 * Запись входного потока в файл.
	 */
	public static File saveStreamToFile(InputStream inputStream, String filename) throws IOException {
		File file = new File(filename);
		try (OutputStream outputStream = new FileOutputStream(file)) {
			proxyStreams(inputStream, outputStream);
		}
		return file;
	}
	
	/**
	 * Направление входного потока в один или более выходных
	 * @param inputStream 
	 */
	public static void proxyStreams(InputStream inputStream, OutputStream... outputStreams) throws IOException {
		int bs = 1024 * 1024;
		byte[] buffer = new byte[bs];
		int bytesRead;
		while ((bytesRead = inputStream.read(buffer)) > 0) {
			for (OutputStream outputStream: outputStreams) {
				outputStream.write(buffer, 0, bytesRead);
			}
		}
	}
	
	/**
	 * Пропуск HTTP-заголовков ответа для получения основной части потока
	 * @param inputStream 
	 */
	public static List<String> skipHttpHeaders(InputStream inputStream) throws IOException {
		//надо читать поток до последовательности сброса строки (байты 13 и/или 10)
		List<String> headers = new ArrayList<>();
		byte[] buffer = new byte[1024];
		int readByte;
		int i = 0;
		while (true) {
			if (i == buffer.length - 1) {
				buffer = Arrays.copyOf(buffer, buffer.length * 2);
			}
			readByte = inputStream.read();
			if (readByte != 10) {
				buffer[i] = (byte)readByte;
				i++;
			} else {
				if ( i > 0 && buffer[i-1] == 13) {
					i--;
				}
				String header = new String(Arrays.copyOfRange(buffer, 0, i));
				if (header.equals("")) {
					break;
				} else {
					headers.add(header);
				}
				i = 0;
			}
		}
		return headers;
	}
	
	/**
	 * Проверка, что ошибка от Oracle содержит сообщение на уровне бизнес-логики Carabi.
	 * Такие ошибки идут с кодом ORA-20010
	 * @param e ошибка от Oracle
	 * @return При наличи подстроки "ORA-20010" &mdash; текст между "ORA-20010" и следующим "ORA-...", иначе null
	 */
	public static String filterCarabiBusinessLogic(SQLException e) {
		String message = e.getMessage();
		final String messageStart = "ORA-20010:";
		final String messageEnd = "ORA-";
		int indexOfStart = message.indexOf(messageStart);
		if (indexOfStart < 0) {
			return null;
		}
		message = message.substring(indexOfStart + messageStart.length());
		int indexOfEnd = message.indexOf(messageEnd);
		message = message.substring(0, indexOfEnd);
		return message.trim();
	}
}

