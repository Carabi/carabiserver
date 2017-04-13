package ru.carabi.server.kernel.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.xml.ws.Holder;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleTypes;
import oracle.sql.ARRAY;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;

/**
 * Выполнение Carabi-запросов в формате XML.
 * Парсинг XML (на стороне Oracle) и осуществление SQL-запроса.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@Stateless
public class XmlQueryBean {
	@EJB
	private CursorFetcherBean cursorFetcher;

	private static final Logger logger = Logger.getLogger(XmlQueryBean.class .getName());
	//Регулярное выражение для подстановки типа документа в SQL-запросы
	private Pattern assignerKindID = Pattern.compile(":LIGHT_KIND_ID");
	
	/**
	 * Получение данных по XML-запросу.
	 * Если данные имеются -- сохранение прокрутки на сервере.
	 * @param logon пользователь Carabi
	 * @param xmlTypefilter XML-код основного фильтра
	 * @param startPos Начало запроса
	 * @param fetchCount Объём запроса
	 * @param queryTag Номер сохранённой прокрутки (при повторном открытии)
	 * @param orderBy
	 * @param idList
	 * @param xmlUserfilter
	 * @param docLike
	 * @param kindId ID типа корневого документа в поиске
	 * @param noCount Не смотреть число объектов перед выборкой
	 * @param browserMask
	 * @param parentId Для иерархических данных &ndash; ID предка искомых (так же 0 &ndash; для корней, -1 &ndash; для всех)
	 * @param columns Список полей в выдаче в формате JSON
	 * @param list Выдача в формате JSON
	 * @param endpos Положение на курсоре после выполнения (startpos + fetchcount, если данные не закончились)
	 * @param lastTag Номер сохранённой прокрутки (для повторного открытия)
	 * @param count Число записей в выдаче
	 * @return Код возврата (код ошибки, 0 или fetchCount)
	 */
	public int docSearchXmlFetch(
			UserLogon logon,
			String xmlTypefilter,
			int startPos,
			int fetchCount,
			int queryTag,
			ArrayList<OrderParameter> orderBy,
			ArrayList<Integer> idList,
			String xmlUserfilter,
			String docLike,
			int kindId,
			int noCount,
			int browserMask,
			int parentId,
			Holder<ArrayList<ArrayList<String>>> columns,
			Holder<ArrayList<ArrayList<?>>> list,
			Holder<Integer> endpos,
			Holder<Integer> lastTag,
			Holder<Integer> count
		) {
		
		try {
			int resultCode = 0;
			endpos.value = startPos;
			lastTag.value = queryTag;
			Fetch fetch = null;
			//Смотрим, открыт ли у нас этот запрос
			if (parentId <= 0) {//Повторно используются только не иерархические запросы
				fetch = cursorFetcher.searchOpenedFetch(logon, queryTag, startPos);
			}
			boolean fetchIsNew = false;
			if (fetch == null) {//если нет -- создаём
				OracleConnection connection = Utls.unwrapOracleConnection(logon.getConnection());
				fetch = createFetch(connection, xmlTypefilter, xmlUserfilter, docLike, orderBy, idList, startPos, kindId, noCount, browserMask, parentId);
				fetchIsNew = true;
			}
			if (fetch == null) {//Ничего не найдено
				return resultCode;
			}
			resultCode = fetchCount;
			ArrayList<ArrayList<?>> data = fetch.processFetching(fetchCount);
			count.value = fetch.getRecordCount();
			endpos.value += data.size();
			//Если пользователь с долгоживущей сессией и новый неиерархический
			//запрос содержит данные -- сохраняем прокрутку
			if (logon.isRequireSession() && fetchIsNew && data.size() > 0 && parentId <= 0) {
				lastTag.value = cursorFetcher.saveFetch(fetch, logon);
			} else {
//				if (data.size() == 0) {
//					resultCode = Settings.SQL_EOF;
//				}
				//закрываем, если кончились данные или пользователь не использует сессию
				//или запрос иерархический
				if (!logon.isRequireSession() || data.isEmpty() || parentId > 0) {
					lastTag.value = -1;
					cursorFetcher.closeFetch(logon, queryTag);
				}
			}
			columns.value = fetch.columns;
			list.value = data;
			return resultCode;
		} catch (CarabiException e) {
			logger.log(Level.WARNING, null, e);
			return e.errorCode;
		}catch (SQLException e) {
			logger.log(Level.SEVERE, null, e);
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

	private Fetch createFetch(OracleConnection connection,
			String xmlTypefilter,
			String xmlUserfilter,
			String docLike,
			ArrayList<OrderParameter> orderBy,
			ArrayList<Integer> idList,
			int startpos,
			int kindId,
			int noCount,
			int browserMask,
			int parentId) throws SQLException, CarabiException {
		GenerateQueryResult gqr = generateQuery(connection, xmlTypefilter, xmlUserfilter, docLike, orderBy, idList, kindId, noCount, browserMask, parentId);
		if (gqr == null) {//Ничего не найдено
			return null;
		} else {
			Fetch f = new Fetch(connection, gqr.sql, startpos);
			f.setRecordCount(gqr.count);
			return f;
		}
	}

	private GenerateQueryResult generateQuery(OracleConnection connection,
			String xmlTypefilter,
			String xmlUserfilter,
			String docLike,
			ArrayList<OrderParameter> orderBy,
			ArrayList<Integer> idList,
			int kindId,
			int noCount,
			int browserMask,
			int parentId) throws SQLException, CarabiException {
		//Создаём SQL из XML и дополнительных условий
		String addQuery = docLike;
		if (!idList.isEmpty()) {
			String idListStr = StringUtils.join(idList, ',');
			addQuery = addQuery + " AND dt.document_id in ("+idListStr+')';
		}
		ARRAY ooXmlTypefilter = stringToOraArray(connection, xmlTypefilter);
		ARRAY ooXmlUserfilter = stringToOraArray(connection, xmlUserfilter);
		ARRAY ooStrFilter = stringToOraArray(connection, addQuery);
		int pMask;
		if (browserMask > 0) { 
			pMask = 17;
		} else {
			pMask = 1;
		}
		
		//Расчитываем кол-во найденных (до сортировки)
		GenerateQueryResult result = new GenerateQueryResult();
		if (noCount == 0) {
			QueryResultHolder tmpQuery = querySet(connection, ooXmlTypefilter, ooXmlUserfilter, ooStrFilter,true,1,pMask);
//			UsrMgr.LogUser(fUserNum,'Поиск по типу', 'kind_id='+IntToStr(kind_id),QSearch.Session,-1);
//			SqlUser('Поиск', true);
//			tmpQuery.Session := QSearch.Session;
			tmpQuery.resultSet.next();
			result.count = tmpQuery.resultSet.getInt(1);
			tmpQuery.resultSet.close();
			tmpQuery.statement.close();
			if (result.count == 0) {//Записей не найдено
				return null;
			}
		}
		if (parentId > -1) {
			addQuery = addQuery + " and (dt.parent = " + String.valueOf(parentId)+')';
		}
		if (orderBy != null && !orderBy.isEmpty()) {
			StringBuilder strSort = new StringBuilder();
			for (OrderParameter orderElement: orderBy) {
				String orderFieldName = orderElement.name;
				boolean orderNumeric = "document_id".equals(orderFieldName.toLowerCase()) ||
						"v.code".equals(orderFieldName.toLowerCase()) ||
						"de.event_date".equals(orderFieldName.toLowerCase()) ||
						orderFieldName.startsWith("P_");
				if (orderNumeric) {//Для цифровых сортировок
					strSort.append(orderFieldName).append(' ').append(orderElement.dir).append(", ");
				} else {//Для символьных сортировок
					strSort.append("UPSET(").append(orderFieldName).append(") ").append(orderElement.dir).append(", ");
				}
			}
			strSort.delete(strSort.length() - 2, strSort.length() - 1);
			addQuery = addQuery + " ORDER BY " + strSort.toString();
			logger.info(addQuery);
		}
		ARRAY ooStrOrder = stringToOraArray(connection, addQuery);
		String queryFilter = queryFilter(connection ,ooXmlTypefilter, ooXmlUserfilter, ooStrOrder, 0, pMask, 1);
		
		
		result.sql = assignerKindID.matcher(queryFilter).replaceAll(""+kindId);
		return result;
	}
	
	private QueryResultHolder querySet(OracleConnection conn, ARRAY ooXmlSys, ARRAY ooXmlUser, ARRAY ooStrFilter, boolean queryOut, int queryMode, int pmask) throws SQLException, CarabiException {
		String sql = 
				"begin\n" +
				"? := pkg_xml.query_set(?, ?, ?, sys.diutil.int_to_bool(?), ?, ?, ?, ?); \n" +
				"end;";
		OracleCallableStatement statement = (OracleCallableStatement) conn.prepareCall(sql);
		statement.registerOutParameter(1, OracleTypes.CURSOR);
		statement.setArray(2, ooXmlSys);
		statement.setArray(3, ooXmlUser);
		statement.setArray(4, ooStrFilter);
		statement.setInt(5, BooleanUtils.toInteger(queryOut));
		statement.setInt(6, queryMode);
		statement.registerOutParameter(7, OracleTypes.ARRAY, "T_VARCHAR2S");
		statement.registerOutParameter(8, OracleTypes.VARCHAR);
		statement.setInt(9, pmask);
		statement.execute();
		String err = statement.getString(8);
		if (StringUtils.isNotEmpty(err)) {
			throw new CarabiException(err, Settings.SQL_ERROR);
		}
		ResultSet resultSet = null;
		String sqlStatement = null;
		if (queryOut) {
			resultSet = statement.getCursor(1);
		} else {
			//statement.close();
			ARRAY sqlStatementWrapper = statement.getARRAY(7);
			sqlStatement = StringUtils.join(sqlStatementWrapper.getArray());
		}
		return new QueryResultHolder(sqlStatement, resultSet, statement);
	}

	private String queryFilter(OracleConnection conn, ARRAY ooXmlSys, ARRAY ooXmlUser, ARRAY ooStrOrder, int queryMode, int pMask, int permissions) throws SQLException, CarabiException {
		String sql = 
				"begin\n" +
				"? := pkg_xml.QUERY_FILTER(?, ?, ?, ?, ?, ?, ?); \n" +
				"end;";
		OracleCallableStatement statement = (OracleCallableStatement) conn.prepareCall(sql);
		statement.registerOutParameter(1, Types.VARCHAR);
		statement.setArray(2, ooXmlSys);
		statement.setArray(3, ooXmlUser);
		statement.setArray(4, ooStrOrder);
		statement.setInt(5, queryMode);
		statement.registerOutParameter(6, OracleTypes.ARRAY, "T_VARCHAR2S");
		statement.setInt(7, pMask);
		statement.setInt(8, permissions);
		statement.execute();
		String err = statement.getString(1);
		if (StringUtils.isNotEmpty(err)) {
			throw new CarabiException(err, Settings.SQL_ERROR);
		}
		ARRAY sqlStatementWrapper = statement.getARRAY(6);
		String sqlStatement = StringUtils.join((String[])sqlStatementWrapper.getArray());
		statement.close();
		return sqlStatement;
	}

	/**
	 * Представление длинных строк в виде Oracle-списка коротких для передачи в PL-функции.
	 * @param conn
	 * @param string
	 * @return объект Oracle для передачи в PL-функцию
	 * @throws SQLException 
	 */
	private ARRAY stringToOraArray(OracleConnection conn, String string) throws SQLException {
		final int STRING_CROP_SIZE = 128;
		ArrayList<String> wrapper = new ArrayList<String>();
		if (string == null) {
			return conn.createARRAY("T_VARCHAR2S", wrapper.toArray());
		}
		int stringsize = string.length();
		int position = 0;
		while (position < stringsize) {
			int nextPosition = position + STRING_CROP_SIZE;
			if (nextPosition > stringsize) {
				nextPosition = stringsize;
			}
			wrapper.add(string.substring(position, nextPosition));
			position = nextPosition;
		}
		return conn.createARRAY("T_VARCHAR2S", wrapper.toArray());
	}
	
	private static class QueryResultHolder {
		public String sqlStatement;//Генерируемый SQL-запрос (при использовании в режиме query_out=false)
		public ResultSet resultSet;//Открытый курсор (при использовании в режиме query_out=true)
		public Statement statement;//Открытое SQL-обращение -- должно быть закрыто вместе с курсором.

		public QueryResultHolder(String sqlStatement, ResultSet resultSet, Statement statement) {
			this.sqlStatement = sqlStatement;
			this.resultSet = resultSet;
			this.statement = statement;
		}
	}
	
	private static class GenerateQueryResult {
		private String sql = null;
		private Integer count = null;
	}
}
