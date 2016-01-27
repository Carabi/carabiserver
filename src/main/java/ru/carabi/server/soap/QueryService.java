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
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.xml.ws.Holder;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import ru.carabi.server.CarabiException;
import ru.carabi.server.CarabiOracleError;
import ru.carabi.server.CarabiOracleMessage;
import ru.carabi.server.RegisterException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;
import ru.carabi.server.Utls;
import ru.carabi.server.kernel.Cache;
import ru.carabi.server.kernel.GuestBean;
import ru.carabi.server.kernel.UsersControllerBean;
import ru.carabi.server.kernel.oracle.CarabiDocumentBean;
import ru.carabi.server.kernel.oracle.CursorFetcherBean;
import ru.carabi.server.kernel.oracle.OrderParameter;
import ru.carabi.server.kernel.oracle.QueryParameter;
import ru.carabi.server.kernel.oracle.QueryStorageBean;
import ru.carabi.server.kernel.oracle.SqlQueryBean;
import ru.carabi.server.kernel.oracle.XmlQueryBean;

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
	@EJB private XmlQueryBean xmlQuery;
	@EJB private SqlQueryBean sqlQuery;
	@EJB private Cache<ArrayList<QueryParameter>> cache;
	@EJB private CursorFetcherBean cursorFetcher;
	@EJB private QueryStorageBean queryStorage;
	@EJB private GuestBean guest;
	@EJB private CarabiDocumentBean carabiDocument;
	private int MAX_NUMBER_OF_WEBUSER_DOCTYPES = 10000;
	private int MAX_NUMBER_OF_DOCUMENT_FILTERS = 1000;
	
	@WebMethod(operationName = "getDocsTableHeader")
	public String getDocsTableHeader(
			@WebParam(name = "token") String token, 
			@WebParam(name = "kindId") Long kindId
		) throws CarabiException {
		logger.log(
			Level.FINE, 
			QueryService.class.getName()+" getDocsTableHeader token={0}, kindId={1}", 
			new Object[]{token, kindId}
			);
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			final ArrayList<ArrayList<?>> docsTableHeader = carabiDocument.getDocsTableHeader(logon, kindId);
			JsonArrayBuilder listToJson = Utls.listToJson(docsTableHeader);
			final String strDocsTableHeader = listToJson.build().toString();
			return strDocsTableHeader;
		}
	}

	@WebMethod(operationName = "getPropertiesList")
	public String getPropertiesList(
			@WebParam(name = "token") String token, 
			@WebParam(name = "kindId") int kindId, 
			@WebParam(name = "documentId") long documentId
		) throws CarabiException {
		logger.log(
			Level.FINE,
			QueryService.class.getName()+" getPropertiesList token={0}, kindId={1}, documentId={2}", 
			new Object[]{token, kindId, documentId}
			);
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			final ArrayList<CarabiDocumentBean.CarabiDocProperty> propertiesList = carabiDocument.getPropertiesList(logon, kindId, documentId);
			logger.log(Level.FINE, "We have {0} properties in service", propertiesList.size());
			final String strPropertiesList = new JSONArray(propertiesList).toString();
			return strPropertiesList;
		}
	}
	
	/**
	 * Показывает список доступных для веб-пользователя документов, определяемый 
	 * в документе "веб-пользователь" в поле "Доступ к объектам". 
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return JSON-строка вида состоящая из массива массивов "DOCKIND_ID", "DOCKIND_DESCR", "DOCKIND_NAME", где
	 * <pre>
	 * DOCKIND_ID - id of document in oracle,
	 * DOCKIND_DESCR - system document type name,
	 * DOCKIND_NAME - end user document type name.
	 * 
	 * вида:
	 * 
	 * [
	 * ["10000","REPORTS","Отчет по типу документа"],
	 * ["18381","DOCQUERY","Запросы"],
	 * ["28346","HOME_REAL","Объект недвижимости (Новостройки)"],
	 * ["28377","FLAT","Типовая квартира"],
	 * ["29944","LOAD_METRO","Метро для выгрузки"]
	 * ]
	 * </pre>
	 */
	@WebMethod(operationName = "getDocKinds")
	public String getDocKinds(@WebParam(name = "token") String token) throws CarabiException {
		logger.log(Level.FINE, "ru.carabi.server.soap.QueryService.getDocKinds called. Params: token={0}.", String.valueOf(token));
		
		// prepare sql params
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			final String webUserId = guest.getWebUserId(logon); // @todo: check result for errors		
			String sql = 
					"SELECT DP.DOC_PROP_VALUE DOCKIND_ID,\n"
							+"GET_VOCAB_VALUE_BY_VALUEID(GET_VOCAB_ID('DOC_KIND'), DP.DOC_PROP_VALUE) DOCKIND_DESCR,\n"
							+"GET_VOCAB_NAME_BY_VALUEID(GET_VOCAB_ID('DOC_KIND'), DP.DOC_PROP_VALUE) DOCKIND_NAME\n"
					+"FROM DOC_PROPERTIES DP\n"
					+"WHERE DP.DOCPROP_ID = GET_DOCPROPID_BYNAME('OBJECTS', 'SYSTEM_USER')\n"
							+"AND DP.DOCUMENT_ID = "+webUserId+"\n"
					+ "ORDER BY DOCKIND_NAME, DOCKIND_DESCR";
			ArrayList<QueryParameter> parameters = new ArrayList<QueryParameter>(0); // @todo: use parameter to pass web user id
			int startPos = 0;
			int fetchCount = MAX_NUMBER_OF_WEBUSER_DOCTYPES;
			int queryTag = -1;
			Holder<ArrayList<ArrayList<String>>> columns = new Holder<>();
			Holder<ArrayList<ArrayList<?>>> list = new Holder<>();
			Holder<Integer> endpos = new Holder<>();
			Holder<Integer> lastTag = new Holder<>();
			Holder<Integer> count = new Holder<>();

			// run oracle query for webuserDocKinds
			Integer r = sqlQuery.selectSql(
				logon, 
				sql,
				parameters,
				startPos,
				fetchCount,
				queryTag,
				columns,
				list,
				endpos,
				lastTag,
				count
				); 

			for (ArrayList record : list.value) {
				if (null==record.get(0) || null==record.get(1) || null==record.get(2)) {
					// todo: log errors and exit
					String msg = "Для данного пользователя не найдено ни одного типа "
								 +"документа разрешенного для просмотра. Для того чтобы "
								 +"разрешить веб-пользователю просмотр и редактирование "
								 +"документов нужно: 1. зайти в главное приложение "
								 +"Carabi-win-client, 2. открыть документ \"WEB пользователь\" "
								 +"во вкладке \"Портал\", 3. в поле \"Доступ к объектам\" "
								 +"выбрать документы, к которым нужно открыть доступ.";
					logger.log(Level.CONFIG, msg);
					throw new CarabiException(msg);
				}
			}
			// todo: make sure its really records count, AS YOU COMMENTED IT 
			// in the bean (or interface), because, as you know, things do really happen

			// stringify results as JSON
			return new JSONArray(list.value).toString();
		}
	}

	/**
	 * Показывает список доступных для заданного типа документов фильтра.
	 * @param token "Токен" (идентификатор) выполненной через сервер приложений регистрации в системе. 
	 * См. 
	 * {@link ru.carabi.server.soap.GuestService}, 
	 * {@link ru.carabi.server.soap.GuestService#registerUserLight(java.lang.String, java.lang.String, java.lang.String, boolean, javax.xml.ws.Holder)} и
	 * {@link ru.carabi.server.soap.GuestService#registerUser(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * @return JSON-строка вида состоящая из массива массивов "DOCUMENT_ID", "DOC_DESCR", "DOC_CONTENT", где
	 * <pre>
	 * DOCUMENT_ID - filter id,
	 * DOC_DESCR - user filter description,
	 * DOC_CONTENT - xml filter description, which may contain parameters to specify.
	 * 
	 * вида:
	 * 
	 * [
	 * ]
	 * </pre>
	 */
	@WebMethod(operationName = "getDocKindFilters")
	public String getDocKindFilters(@WebParam(name = "token") String token, @WebParam(name = "kindId") int kindId) 
			throws CarabiException, CarabiOracleError
	{
		logger.log(Level.FINE, "ru.carabi.server.soap.QueryService.getDocKindFilters called. Params: token={0}.", token);
		
		// prepare sql params
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			String sql = 
					"select d.document_id,\n" +
					"       d.doc_descr,\n" +
					"       d.doc_kind,\n" +
					"       get_query_permission(d.document_id) perm,\n" +
	//				"		utl_raw.cast_to_varchar2(dbms_lob.substr(d.doc_content)),\n" +
					"		d.doc_content\n" +
	//				"		PKG_CARABI_XML.TO_CLOB(d.doc_content)\n" +				
					"from documents_tree d\n" +
					"where parent = 0\n" +
					"   and d.doc_name in '?'\n" +
					"   and d.dockind_id = "+String.valueOf(kindId)+"\n" +
					"   and ((documents.get_role_id = 847) or\n" +
					"       documents.get_role_id in\n" +
					"       (select p.userrole_id\n" +
					"           from doc_permissions p\n" +
					"          where p.document_id = d.document_id) or\n" +
					"       documents.get_user_id in\n" +
					"       (select p.ct_item_id\n" +
					"           from doc_permissions p\n" +
					"          where p.document_id = d.document_id))";
			ArrayList<QueryParameter> parameters = new ArrayList<QueryParameter>(0); // @todo: use parameter to pass kind id
			int startPos = 0;
			int fetchCount = MAX_NUMBER_OF_DOCUMENT_FILTERS;
			int queryTag = -1;
			Holder<ArrayList<ArrayList<String>>> columns = new Holder<>();
			Holder<ArrayList<ArrayList<?>>> list = new Holder<>();
			Holder<Integer> endpos = new Holder<Integer>();
			Holder<Integer> lastTag = new Holder<Integer>();
			Holder<Integer> count = new Holder<Integer>();

			// run oracle query for webuserDocKinds
			Integer r = sqlQuery.selectSql(
				logon, 
				sql,
				parameters,
				startPos,
				fetchCount,
				queryTag,
				columns,
				list,
				endpos,
				lastTag,
				count
				); 
			if (r<0) {
				// todo: log errors and exit
				String msg = "При получении списка фильтров возникла ошибка обработки sql-запроса: "+String.valueOf(r)+".";
				logger.log(Level.CONFIG, msg);
				throw new CarabiException(msg);
			}
			// @todo: make sure its really records count, AS YOU COMMENTED IT 
			// todo: in the bean (or interface), because, as you know, things do really happen		

			// convert blob to string for each xmlFilter
			for (int i=0; i<list.value.size(); i++) {
				final ArrayList filterRecord = (ArrayList)list.value.get(i);
				java.sql.Blob blobXmlFilter = (java.sql.Blob) filterRecord.get(4);
				final String stringXmlFilter;
				try {
					stringXmlFilter = getStringFromInputStream(blobXmlFilter.getBinaryStream());
				} catch (IOException ex) {
					String msg = "Внутренняя ошибка обработки пользовательского фильтра: не удается преобразовать java.sql.Blob в java.lang.String. Получение фильтров будет отменено.";
					logger.log(Level.SEVERE, msg);
					throw new CarabiException(msg, ex);
				} catch (SQLException ex) {
					Logger.getLogger(QueryService.class.getName()).log(Level.SEVERE, null, ex);
					throw new CarabiOracleError(ex);
				}
				filterRecord.set(4, stringXmlFilter);
			}

			// stringify results as JSON
			return new JSONArray(list.value).toString(); 
		}
	}
	
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
	 * Ищет документы Carabi по XML-запросу.
	 * Поиск производится в формализованной базе документов с использованием
	 * структурированного запроса на языке XML, преобразуемого в SQL на стороне БД.
	 * Запрос производится к базе данных, выбранной при авторизации.
	 * Функция сохраняет <code>ResultSet</code> в прокрутке ({@link ru.carabi.server.kernel.oracle.Fetch}), возвращает
	 * шапку и первые <code>fetchCount</code> записей. Можно использовать параметры
	 * сортировки, уточнить запрос, указать имя Oracle-сессии. Если прокрутка уже
	 * была открыта &ndash; можно обратиться к ней повторно этой же функцией или
	 * {@linkplain #fetchNext(java.lang.String, int, int, int, javax.xml.ws.Holder, javax.xml.ws.Holder)}.
	 * Если из прокрутки не были считаны все записи &ndash; следует закрыть её,
	 * используя функцию {@linkplain #closeFetch(java.lang.String, int) } или
	 * {@linkplain #closeAllFetches(java.lang.String)}
	 * 
	 * @param token авторизационный токен
	 * @param sessionName имя сессии в Oracle (если требуется)
	 * @param xmlTypefilter XML-запрос
	 * @param startPos номер записи, начиная с которой выводим результат (при открытии нового запроса &ndash; обычно 0)
	 * @param fetchCount количество записей, возвращаемых в <code>listJson</code>
	 * @param queryTag номер открытого запроса (-1, если открываем новый)
	 * @param orderBy параметры сортировки
	 * @param idList список документов, в которых будет производиться поиск
	 * @param xmlUserfilter дополнительный XML-запрос (для вложенного поиска)
	 * @param docLike дополнительный SQL-запрос 
	 * @param kindId ID типа искомого документа
	 * @param nocount не проверять количество объектов перед запросом
	 * @param browserMask
	 * @param parentId для иерархических данных &ndash; ID предка искомых (так же 0 &ndash; для корней, -1 &ndash; для всех)
	 * @param columnsJson выходной параметр &ndash; список полей выдачи в виде JSON-массива
	 * @param listJson выходной параметр &ndash; выдача в виде JSON-массива
	 * @param endpos выходной параметр &ndash; текущая позиция в выдаче после выполнения функции
	 * @param lastTag выходной параметр &ndash; номер открытого запроса для дальнейшего просмотра
	 * @param count выходной параметр &ndash; количество возвращёных записей
	 * @return <ul>
	 *		<li><code>fetchCount</code>при успешном выполнении;</li>
	 *		<li>0, если данные не найдены;</li>
	 *		<li>{@link Settings#SQL_EOF}, если данные закончились;</li>
	 *		<li>{@link Settings#OPENED_FETCHES_LIMIT_ERROR}, если пользователь сохранил более {@link Settings#FETCHES_BY_USER} прокруток;</li>
	 *		<li>{@link Settings#SQL_ERROR} при ошибке выполнения запроса;</li>
	 *		<li>{@link Settings#NO_SUCH_USER} если подан незарегистрированный или устаревший токен.</li>
	 * </ul>
	 */
	@WebMethod(operationName = "docSearchXml")
	public int docSearchXml(
			@WebParam(name = "token") String token,
			@WebParam(name = "sessionName") String sessionName,
			@WebParam(name = "xmlTypefilter") final String xmlTypefilter,
			@WebParam(name = "startPos") int startPos,
			@WebParam(name = "fetchCount") int fetchCount,
			@WebParam(name = "queryTag") int queryTag,
			@WebParam(name = "orderBy") ArrayList<OrderParameter> orderBy,
			@WebParam(name = "idList") ArrayList<Integer> idList,
			@WebParam(name = "xmlUserfilter") final String xmlUserfilter,
			@WebParam(name = "docLike") String docLike,
			@WebParam(name = "kindId") int kindId,
			@WebParam(name = "nocount") int nocount,
			@WebParam(name = "browserMask") int browserMask,
			@WebParam(name = "parentId") int parentId,
			@WebParam(name = "columnsJson", mode= WebParam.Mode.OUT) Holder<String> columnsJson,
			@WebParam(name = "listJson", mode= WebParam.Mode.OUT) Holder<String> listJson,
			@WebParam(name = "endpos", mode= WebParam.Mode.OUT) Holder<Integer> endpos,
			@WebParam(name = "lastTag", mode= WebParam.Mode.OUT) Holder<Integer> lastTag,
			@WebParam(name = "count", mode= WebParam.Mode.OUT) Holder<Integer> count
		) throws CarabiException
	{
		// log call & params
		StringBuilder sb = new StringBuilder();
		sb.append("ru.carabi.server.soap.QueryService docSearchXML called with params: ");
		sb.append("token=").append(token);
		sb.append(", sessionName=").append(sessionName);
		sb.append(", xmlTypefilter=").append(xmlTypefilter);
		sb.append(", startPos=").append(startPos);
		sb.append(", fetchCount=").append(fetchCount);
		sb.append(", queryTag=").append(queryTag);
		sb.append(", orderBy=").append(queryTag);
		sb.append(", orderBy=").append(orderBy);
		sb.append(", idList=").append(idList);
		sb.append(", xmlUserfilter=").append(xmlUserfilter);
		sb.append(", docLike=").append(docLike);
		sb.append(", kindId=").append(kindId);
		sb.append(", nocount=").append(nocount);
		sb.append(", browserMask=").append(browserMask);
		sb.append(", parentId=").append(parentId);
		sb.append(".");
		logger.finest(sb.toString());
		
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			Holder<ArrayList<ArrayList<String>>> columns = new Holder<>();
			Holder<ArrayList<ArrayList<?>>> list = new Holder<>();
			int result = xmlQuery.docSearchXmlFetch(logon, xmlTypefilter, startPos, fetchCount, queryTag, orderBy, idList, xmlUserfilter, docLike, kindId, nocount, browserMask, parentId, columns, list, endpos, lastTag, count);
			columnsJson.value = new JSONArray(columns.value).toString();
			listJson.value = new JSONArray(list.value).toString();
			return result;
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
	
	/**
	 * Получение Караби-таблицы (wave-таблицы)
	 * @param token авторизационный токен
	 * @param sessionName Ключ кеша
	 * @param tableName имя Караби-таблицы
	 * @param fetchCount число возвращаемых записей. Сохранение прокрутки пока не предусмотрено.
	 * @param fetchAll вернуть все строки курсоров независимо от fetchCount
	 * @param documentsIds список документов, по которым получаем данные
	 * @return 
	 */
//	 * Если указано положительное количество (или 0) &ndash; курсор сохраняется в прокрутке,
//	 * если отрицательное &ndash; закрывается.

	@WebMethod(operationName = "getCarabiTable")
	public String getCarabiTable(
			@WebParam(name = "token") String token,
			@WebParam(name = "sessionName") String sessionName,
			@WebParam(name = "tableName") String tableName,
			@WebParam(name = "fetchCount") int fetchCount,
			@WebParam(name = "fetchAll") boolean fetchAll,
			@WebParam(name = "documentsIds") ArrayList<Long> documentsIds
	) throws CarabiException {
		logger.log(
				Level.FINE, 
				"getCarabiTable token={0}, sessionName={1}, tableName={2}, fetchCount={3}, fetchAll={4}",
				new Object[] {token, sessionName, tableName, fetchCount, fetchAll}
			);
		if (fetchAll) {
			fetchCount = Integer.MAX_VALUE;
		}
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			QueryParameter carabiTable = sqlQuery.getCarabiTable(logon, tableName, documentsIds, fetchCount);
			wrapJson(carabiTable);
			return carabiTable.getValue();
		} catch (SQLException ex) {
			Logger.getLogger(QueryService.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiOracleError(ex);
		}
	}
	
	/**
	 * Запуск запросов для системы отчётов.
	 * Получает SQL-код, используя метод APPL_REPORT_QUERY.GET_QUERY_SQL
	 * и запускает его с параметрами, установленными через TYPES_CRITERIES.SET_CRITERIES
	 * @param token авторизационный токен
	 * @param reportId id документа с отчётом
	 * @param queryName название запроса ("MAIN" &mdash; основной запрос отчёта, другое имя -- вспомогательные)
	 * @param criteries параметры-критерии в формате имя|значение`имя2|значение2`имя3|значение3
	 * @return выборка в вормате JSON-объекта с элементами columns (список полей) и list (строки)
	 * @throws CarabiException 
	 */
	@WebMethod(operationName = "runCarabiReportQuery")
	public String runCarabiReportQuery(
			@WebParam(name = "token") String token,
			@WebParam(name = "reportId") long reportId,
			@WebParam(name = "queryName") String queryName,
			@WebParam(name = "criteries") String criteries
		) throws CarabiException {
		logger.log(
				Level.FINE, 
				"runCarabiReportQuery token={0}, reportId={1}, queryName={2},  criteries={3}",
				new Object[] {token, reportId, queryName, criteries}
			);
		
		try (UserLogon logon = usersController.tokenAuthorize(token)) {
			String getSqlQuery = " BEGIN\n" +
				"  :TARGET_QUERY := APPL_REPORT_QUERY.GET_QUERY_SQL(REPORT_ID$ => :REPORT_ID,\n" +
				"                                             QUERY_NAME$ => :QUERY_NAME);\n" +
				"END;";
			ArrayList<QueryParameter> getSqlQueryParameters = new ArrayList<>(3);
			QueryParameter targetQuery = new QueryParameter("TARGET_QUERY", "", true);
			getSqlQueryParameters.add(targetQuery);
			QueryParameter reportIdParam = new QueryParameter("REPORT_ID", reportId, false);
			getSqlQueryParameters.add(reportIdParam);
			QueryParameter queryNameParam = new QueryParameter("QUERY_NAME", queryName, false);
			getSqlQueryParameters.add(queryNameParam);
			Holder<ArrayList<QueryParameter>> getSqlQueryParametersHolder = new Holder<>(getSqlQueryParameters);
			sqlQuery.executeScript(logon, getSqlQuery, getSqlQueryParametersHolder, -1);
			String targetSqlValue = "BEGIN\n" +
			"  TYPES_CRITERIES.SET_CRITERIES(CRITERIES$        => :CRITERIES,\n" +
			"                                VALUE_DELIMITER$  => '|',\n" +
			"                                STRING_DELIMITER$ => '`');\n" +
			"\n" +
			"  OPEN :RESULT_CURSOR FOR\n" +
			targetQuery.getValue() + ";\n" +
			"END;";
			ArrayList<QueryParameter> reportParameters = new ArrayList<>();
			QueryParameter criteriesParam = new QueryParameter("CRITERIES", criteries, false);
			reportParameters.add(criteriesParam);
			QueryParameter resultCursor= new QueryParameter("RESULT_CURSOR", null, true);
			resultCursor.setType("CURSOR");
			reportParameters.add(resultCursor);
			Holder<ArrayList<QueryParameter>> reportParametersHolder = new Holder<>(reportParameters);
			sqlQuery.executeScript(logon, targetSqlValue, reportParametersHolder, Integer.MAX_VALUE);
			wrapJson(resultCursor);
			return resultCursor.getValue();
		} catch (SQLException ex) {
			Logger.getLogger(QueryService.class.getName()).log(Level.SEVERE, null, ex);
			throw new CarabiOracleError(ex);
		}
	}
	
	private void wrapJson(Holder<ArrayList<QueryParameter>> parameters) throws SQLException {
		for (QueryParameter queryParameter: parameters.value) {
			wrapJson(queryParameter);
		}
	}
	
	private void wrapJson(QueryParameter queryParameter) throws SQLException {
		if ("CURSOR".equals(queryParameter.getType())) {
			Map cursorData = (Map) queryParameter.getValueObject();
			JsonObjectBuilder cursorObject = Utls.mapToJson(cursorData);
			cursorObject.add("queryTag", queryParameter.getValue());
			queryParameter.setValue(cursorObject.build().toString());
			queryParameter.setValueObject(null);
		} else if ("CLOB".equals(queryParameter.getType())) {
			if ("CLOB_AS_VARCHAR".equals(queryParameter.getValue())) {
				queryParameter.setType("VARCHAR2");
			} else if ("CLOB_AS_CURSOR".equals(queryParameter.getValue())) {
				queryParameter.setType("CURSOR");
			} else {
				queryParameter.setType("CLOB");
			}
			oracle.sql.CLOB clob = (oracle.sql.CLOB)queryParameter.getValueObject();
			queryParameter.setValue(clob.stringValue());
			queryParameter.setValueObject(null);
		}
	}
}
