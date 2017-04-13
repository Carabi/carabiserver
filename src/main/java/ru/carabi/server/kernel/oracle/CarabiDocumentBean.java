/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ru.carabi.server.kernel.oracle;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.xml.ws.Holder;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.logging.CarabiLogging;

/**
 * @author sasha,misha
 */
@Stateless
public class CarabiDocumentBean {
	private static final String Q_DOC_F_GET_PROPERTIES_LIST = "QDocFGetPropertiesList"; // get properties  
	private static final Logger logger = CarabiLogging.getLogger(CarabiDocumentBean.class); // logger
	//@EJB private UsersController usersController;
	//@EJB private XmlQuery xmlQuery;
	@EJB private SqlQueryBean sqlQuery;
	//@EJB private CursorFetcher cursorFetcher;
	@EJB private QueryStorageBean queryStorage;
	//@EJB private Guest guest=null;
	//private int MAX_NUMBER_OF_DOCHEADER_FIELDS = 10000;
	private int MAX_NUMBER_OF_WEBUSER_DOCTYPES = 10000;
	//private int MAX_NUMBER_OF_DOCUMENT_FILTERS = 1000;
	
	private UserLogon logon; // I don't think we need this. Pls, consider. Reason: must not be a field
	private int typeId;
	private int status;
	
	public ArrayList<ArrayList<?>> getDocsTableHeader(UserLogon logon, Long kindId) throws CarabiException {
		logger.log(Level.INFO, "ru.carabi.server.kernel.oracle.CarabiDocumentBean.getDocsTableHeader called. Params: docKindId={0}.", kindId);
		
		// prepare sql params
		String sql = 
			"SELECT\n" +
			"		  dkp.DOCPROP_ID,\n" +
			"		  dkp.DOCPROP_DESCR,\n" +
			"		  dkp.DOCPROP_NAME as DOC_CAPTION,\n" +
			"		  (select mu_name from measuring_unit where measuring_unit.mu_id = doc_mu_kind) as DOC_MU_NAME,\n" +
			"		  dkp.DOCPROP_KIND,\n" +
			"		  dkp.DOCPROP_OBJECT\n" +
			"		  --NVL(dkn.show_order,-1) as DOCPROP_DISPLAY_ORDER,\n" +
			"		  --dkn.name_prefix||CHR(4)||dkn.name_suffix as DOCPROP_DISPLAY_COMMENT\n" +
			"		FROM\n" +
			"		  doc_kind_properties dkp,\n" +
			"		  doc_kind_name dkn\n" +
			"		WHERE\n" +
			"		  (dkp.DOCKIND_ID = "+kindId+")\n" +
			"		and dkn.docprop_id(+) = dkp.docprop_id\n" +
			"		and dkn.show_order >= 0\n" +
			"		ORDER BY\n" +
			"		  dkn.show_order";
		
		ArrayList<QueryParameter> parameters = new ArrayList<>(0); // @todo: use parameter 
		int startPos = 0;
		int fetchCount = MAX_NUMBER_OF_WEBUSER_DOCTYPES;
		int queryTag = -1;
		Holder<ArrayList<ArrayList<String>>> columns = new Holder<>();
		Holder<ArrayList<ArrayList<?>>> list = new Holder<>();
		Holder<Integer> endpos = new Holder<Integer>();
		Holder<Integer> lastTag = new Holder<Integer>();
		Holder<Integer> count = new Holder<Integer>();

		// run oracle query for webuserDocKinds
		int r = sqlQuery.selectSql(
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
			// log errors and exit
			String msg = "Sql получения свойств объекта возвратил код ошибки: "+r;
			logger.log(Level.CONFIG, msg);
			throw new CarabiException(msg);
		}
		
		return list.value;
	}
	
	/**
	 * Получение списка свойств формализованного документа без значений.
	 * Функция получает список полей карточки определённого типа в определённом статусе.
	 * На вход может быть подан ID типа и/или ID документа. Если подан документа -- берутся его
	 * тип и статус. Если только ID типа -- используется системный статус "created".
	 * 
	 * @param logon сессия пользователя
	 * @param typeId ID типа документа (можно 0, если задан документ)
	 * @param documentId ID документа (или 0, если задан тип и не требуется конкретный статус)
	 * @return
	 * @throws CarabiException 
	 */
	public ArrayList<CarabiDocProperty> getPropertiesList(UserLogon logon, int typeIdInput, long documentId)  throws CarabiException {
		logger.log(Level.INFO, "ru.carabi.server.kernel.oracle.CarabiDocumentBean.getPropertiesList called. Params: type_id={0}, document_id={1}.", new Object[]{typeIdInput, documentId});
		try {
			if (documentId > 0) {//если документ задан -- берём из него тип и статус, сверяем тип.
				status = getDocumentStatus(logon, documentId);
				typeId = getDockindID(logon, documentId);
				if (typeIdInput > 0 && typeIdInput != typeId) {
					throw new CarabiException("getPropertiesList called with wrong parameters. Document " + documentId +" do not have type " + typeIdInput);
				}
			} else {//без документа -- входной тип, статус "Создан".
				if (typeIdInput <= 0) {
					throw new CarabiException("getPropertiesList called with wrong parameters. Neither document, nor type is set.");
				}
				typeId = typeIdInput;
				status = getDoceventkindId(logon, "created");
			}
			
			ArrayList<QueryParameter> parameters = new ArrayList<QueryParameter>(4);
			QueryParameter documentStatus = new QueryParameter();
			documentStatus.setValue(""+status);
			parameters.add(documentStatus);
			QueryParameter documentType = new QueryParameter();
			documentType.setValue(""+typeId);
			parameters.add(documentType);
			QueryParameter selected = new QueryParameter();
			parameters.add(selected);
			queryStorage.runQuery(logon, Q_DOC_F_GET_PROPERTIES_LIST, parameters, -Integer.MAX_VALUE);
			ArrayList<LinkedHashMap<String, ?>> docProperties = selected.getCursorRedim();
			ArrayList<CarabiDocProperty> result = new ArrayList<CarabiDocProperty>(docProperties.size());
			this.logon = logon;
			for (LinkedHashMap<String, ?> docPropertyData: docProperties) {
				result.add(new CarabiDocProperty(docPropertyData));
			}
			return result;
		} catch (SQLException ex) {
			logger.log(Level.SEVERE, null, ex);
		} finally {
			this.logon = null;
			typeId = 0;
			status = 0;
		}
		return null;
	}

	/**
	 * Получение системного наименования типа формализованного документа.
	 * @param logon сессия пользователя
	 * @param documentId ID формализованного документа
	 * @return
	 * @throws CarabiException
	 * @throws SQLException 
	 */
	public String getDockindName(UserLogon logon, long documentId) throws CarabiException, SQLException {
		String dockindName = (String)queryStorage.runSimpleQuery(logon, "getDockindName", ""+documentId);
		return dockindName;
	}
	
	/**
	 * Получение ID типа формализованного документа.
	 * @param logon сессия пользователя
	 * @param documentId ID формализованного документа
	 * @return
	 * @throws CarabiException
	 * @throws SQLException 
	 */
	public int getDockindID(UserLogon logon, long documentId) throws CarabiException, SQLException {
		BigDecimal dockindID = (BigDecimal)queryStorage.runSimpleQuery(logon, "getDockindID", ""+documentId);
		return dockindID.intValue();
	}
	
	/**
	 * Получение статуса формализованного документа.
	 * @param logon сессия пользователя
	 * @param documentId ID формализованного документа
	 * @return ID статуса
	 * @throws CarabiException
	 * @throws SQLException 
	 */
	public int getDocumentStatus(UserLogon logon, long documentId) throws CarabiException, SQLException {
		BigDecimal statusID = (BigDecimal)queryStorage.runSimpleQuery(logon, "DocfGetStatus", ""+documentId);
		return statusID.intValue();
	}
	
	/**
	 * получает идентификатор системного события по его наименованию
	 */
	public int getDoceventkindId(UserLogon logon, String eventName) throws CarabiException, SQLException {
		BigDecimal statusID = (BigDecimal)queryStorage.runSimpleQuery(logon, "QDocFGetDocEventKindID", eventName);
		return statusID.intValue();
	}

	/**
	 * возвращает значение доступа для указанных структур ФМД
	 * @param dockindId ID типа документа
	 * @param doceventkindId ID статуса или null
	 * @param docpropId ID поля или null
	 */
	public int docFGetPermissions(int dockindId, Integer doceventkindId, Integer docpropId) throws CarabiException, SQLException {
		ArrayList<QueryParameter> parameters = new ArrayList<QueryParameter>(4);
		QueryParameter dockindIdP = new QueryParameter();
		dockindIdP.setValue(""+dockindId);
		parameters.add(dockindIdP);
		QueryParameter doceventkindIdP = new QueryParameter();
		if (doceventkindId == null) {
			doceventkindIdP.setIsNull(1);
		} else {
			doceventkindIdP.setValue(doceventkindId.toString());
		}
		parameters.add(doceventkindIdP);
		QueryParameter docpropIdP = new QueryParameter();
		if (docpropId == null) {
			docpropIdP.setIsNull(1);
		} else {
			docpropIdP.setValue(docpropId.toString());
		}
		parameters.add(docpropIdP);
		queryStorage.runQuery(logon, "docfGetPermissions", parameters, -1);
		QueryParameter permissions = parameters.get(3);
		return ((BigDecimal) permissions.getCursorRedim().get(0).get("PERMISSIONS")).intValue();
	}
	
	/**
	 * возвращает 1, если есть возможность связывания типа: если нет обратной ссылки, в которой нет множественности и которая обязательна для заполнения
	 * @param parentKindId ID родительского типа
	 * @param childKindId ID дочернего типа
	 * @return
	 * @throws CarabiException
	 * @throws SQLException 
	 */
	public int isDocKindCanLink(int parentKindId, int childKindId) throws CarabiException, SQLException { 
		ArrayList<QueryParameter> parameters = new ArrayList<QueryParameter>(3);
		QueryParameter parentKindIdP = new QueryParameter();
		parentKindIdP.setValue(""+parentKindId);
		parameters.add(parentKindIdP);
		QueryParameter childKindIdP = new QueryParameter();
		childKindIdP.setValue(""+childKindId);
		parameters.add(childKindIdP);
		queryStorage.runQuery(logon, "qDocFIsDocKindCanLink", parameters, -1);
		QueryParameter canLink = parameters.get(2);
		return ((BigDecimal) canLink.getCursorRedim().get(0).get("DOCKIND_CAN_LINK")).intValue();
	}

	
	public class CarabiDocProperty {
		int DOCPROP_ID;
		String DOC_MU_NAME;
		int DOC_FORMAT;
		String DOCPROP_DESCR;
		String DOCPROP_NAME;
		int DOCPROP_KIND;
		int DOCPROP_VISIBLE;
		int DOCPROP_notNULL;
		int DOCPROP_UNIQUE;
		String DOCPROP_OBJECT;
		int DOCPROP_REPEAT;
		int DOCPROP_MULTI;
		String DOCPROP_SQL;
		int SHOW_ORDER;
		int DOCPROP_DISPLAY_ORDER;
		String DOCPROP_DISPLAY_COMMENT;
		int DOCPROP_PERMISSION;
		String DOCPROP_FPATH;
		String DOCPROP_SCRIPT;
		ArrayList<CarabiRefProperty> REFPROP_DATA;
		int DOCPROP_VLEVEL;
		int DOCPROP_RULE;
		
		private CarabiDocProperty(LinkedHashMap<String, ?> docPropertyData) throws CarabiException, SQLException {
			// 1. добавление информации о реквизите
			DOCPROP_ID = ((BigDecimal)docPropertyData.get("DOCPROP_ID")).intValue();
			DOC_MU_NAME = (String) docPropertyData.get("DOC_MU_NAME");
			DOC_FORMAT = ((BigDecimal)docPropertyData.get("DOC_FORMAT")).intValue();
			DOCPROP_DESCR = (String) docPropertyData.get("DOCPROP_DESCR");
			DOCPROP_NAME = (String) docPropertyData.get("DOCPROP_NAME");
			DOCPROP_KIND = ((BigDecimal)docPropertyData.get("DOCPROP_KIND")).intValue();
			DOCPROP_VISIBLE = ((BigDecimal)docPropertyData.get("DOCPROP_VISIBLE")).intValue();
			DOCPROP_notNULL = ((BigDecimal)docPropertyData.get("DOCPROP_NOTNULL")).intValue();
			DOCPROP_UNIQUE = ((BigDecimal)docPropertyData.get("DOCPROP_UNIQUE")).intValue();
			DOCPROP_OBJECT = (String) docPropertyData.get("DOCPROP_OBJECT");
			DOCPROP_REPEAT = ((BigDecimal)docPropertyData.get("DOCPROP_REPEAT")).intValue();
			DOCPROP_MULTI = ((BigDecimal)docPropertyData.get("DOCPROP_MULTI")).intValue();
			DOCPROP_SQL = (String) docPropertyData.get("DOCPROP_SQL");
			SHOW_ORDER = ((BigDecimal)docPropertyData.get("SHOW_ORDER")).intValue();
			DOCPROP_DISPLAY_ORDER = ((BigDecimal)docPropertyData.get("DOCPROP_DISPLAY_ORDER")).intValue();
			DOCPROP_DISPLAY_COMMENT = (String) docPropertyData.get("DOCPROP_DISPLAY_COMMENT");
			DOCPROP_PERMISSION = ((BigDecimal)docPropertyData.get("DOCPROP_PERMISSION")).intValue();
			DOCPROP_FPATH = (String) docPropertyData.get("DOCPROP_FPATH");
			DOCPROP_SCRIPT = (String) docPropertyData.get("DOCPROP_SCRIPT");
			REFPROP_DATA = new ArrayList<CarabiRefProperty>();
			DOCPROP_VLEVEL = ((BigDecimal)docPropertyData.get("DOCPROP_VLEVEL")).intValue();
			DOCPROP_RULE = ((BigDecimal)docPropertyData.get("DOCPROP_RULE")).intValue();
			// 2. Для ссылочных полей -- добавление информации о типах возможных документов для организации связей
			// ДОБАВЛЕНИЕ ДАННЫХ ДЛЯ ССЫЛОЧНЫХ ПОЛЕЙ
			int docpropKind = DOCPROP_KIND;
			String docpropObject = DOCPROP_OBJECT;
			// перебор по ссылкам на документы
			if (docpropKind == 9) {//прямая ссылка
				ArrayList<QueryParameter> parameters = new ArrayList<QueryParameter>(4);
				QueryParameter docpropId = new QueryParameter();
				docpropId.setValue(""+DOCPROP_ID);
				parameters.add(docpropId);
				QueryParameter result = new QueryParameter();
				parameters.add(result);
				queryStorage.runQuery(logon, "qDocFNavGetRefInfo", parameters, -Integer.MAX_VALUE);
				for (LinkedHashMap<String, ?> refInfo: result.getCursorRedim()) {
					if (refInfo == null) {
						logger.log( Level.WARNING, "docReference {0} has empty targets", DOCPROP_ID);
						continue;
					}
					int dockindId = ((BigDecimal)refInfo.get("DOCKIND_ID")).intValue();
					int dockindPermission = docFGetPermissions(dockindId, null, null);
					int dockindCanLink = isDocKindCanLink(typeId, dockindId);
					CarabiRefProperty refProperty = new CarabiRefProperty(
						dockindId,
						(String) refInfo.get("DOCKIND_DESCR"),
						dockindPermission,
						dockindCanLink,
						(String) refInfo.get("DOCKIND_FILTER")
					);
					REFPROP_DATA.add(refProperty);
				}
			} else if(docpropKind == 12) {//обратная ссылка
				try {//if (NumberUtils.isNumber(docpropObject)) {
					int dockindId = Integer.parseInt(docpropObject);
					int dockindPermission = docFGetPermissions(dockindId, null, null);
					CarabiRefProperty refProperty = new CarabiRefProperty(
						dockindId,
						"",
						dockindPermission,
						0,
						""
					);
					REFPROP_DATA.add(refProperty);
				} catch (NumberFormatException e) {}
			}
		}
		
		public int getDOCPROP_ID() {
			return DOCPROP_ID;
		}
		public String getDOC_MU_NAME() {
			return DOC_MU_NAME;
		}
		public int getDOC_FORMAT() {
			return DOC_FORMAT;
		}
		public String getDOCPROP_DESCR() {
			return DOCPROP_DESCR;
		}
		public String getDOCPROP_NAME() {
			return DOCPROP_NAME;
		}
		public int getDOCPROP_KIND() {
			return DOCPROP_KIND;
		}
		public int getDOCPROP_VISIBLE() {
			return DOCPROP_VISIBLE;
		}
		public int getDOCPROP_notNULL() {
			return DOCPROP_notNULL;
		}
		public int getDOCPROP_UNIQUE() {
			return DOCPROP_UNIQUE;
		}
		public String getDOCPROP_OBJECT() {
			return DOCPROP_OBJECT;
		}
		public int getDOCPROP_REPEAT() {
			return DOCPROP_REPEAT;
		}
		public int getDOCPROP_MULTI() {
			return DOCPROP_MULTI;
		}
		public String getDOCPROP_SQL() {
			return DOCPROP_SQL;
		}
		public int getSHOW_ORDER() {
			return SHOW_ORDER;
		}
		public int getDOCPROP_DISPLAY_ORDER() {
			return DOCPROP_DISPLAY_ORDER;
		}
		public String getDOCPROP_DISPLAY_COMMENT() {
			return DOCPROP_DISPLAY_COMMENT;
		}
		public int getDOCPROP_PERMISSION() {
			return DOCPROP_PERMISSION;
		}
		public String getDOCPROP_FPATH() {
			return DOCPROP_FPATH;
		}
		public String getDOCPROP_SCRIPT() {
			return DOCPROP_SCRIPT;
		}
		public ArrayList<CarabiRefProperty> getREFPROP_DATA() {
			return REFPROP_DATA;
		}
		public int getDOCPROP_VLEVEL() {
			return DOCPROP_VLEVEL;
		}
		public int getDOCPROP_RULE() {
			return DOCPROP_RULE;
		}
	}
	
	public static class CarabiRefProperty {
		int DOCKIND_ID;
		String DOCKIND_DESCR;
		int DOCKIND_PERMISSION;
		int DOCKIND_CAN_LINK;
		String DOCKIND_FILTER;

		public CarabiRefProperty(int DOCKIND_ID, String DOCKIND_DESCR, int DOCKIND_PERMISSION, int DOCKIND_CAN_LINK, String DOCKIND_FILTER) {
			this.DOCKIND_ID = DOCKIND_ID;
			this.DOCKIND_DESCR = DOCKIND_DESCR;
			this.DOCKIND_PERMISSION = DOCKIND_PERMISSION;
			this.DOCKIND_CAN_LINK = DOCKIND_CAN_LINK;
			this.DOCKIND_FILTER = DOCKIND_FILTER;
		}

		public int getDOCKIND_ID() {
			return DOCKIND_ID;
		}
		public String getDOCKIND_DESCR() {
			return DOCKIND_DESCR;
		}
		public int getDOCKIND_PERMISSION() {
			return DOCKIND_PERMISSION;
		}
		public int getDOCKIND_CAN_LINK() {
			return DOCKIND_CAN_LINK;
		}
		public String getDOCKIND_FILTER() {
			return DOCKIND_FILTER;
		}
	}
}
