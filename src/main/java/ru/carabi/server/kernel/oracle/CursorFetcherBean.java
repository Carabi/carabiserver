package ru.carabi.server.kernel.oracle;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.ejb.Singleton;
import javax.xml.ws.Holder;
import ru.carabi.server.CarabiException;
import ru.carabi.server.Settings;
import ru.carabi.server.UserLogon;

/**
 * Управление прокрутками ({@link Fetch}).
 * Singleton-модуль, хранящий открытые пользовательские прокрутки.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Singleton
public class CursorFetcherBean {
	
	//прокрутки, открытые пользователями
	private final Map<String, Map<Integer, Fetch>> fetchesForUsers= new ConcurrentHashMap<>();
	//Номер сохранённой прокрутки у пользователя
	private final Map<String, Integer> usersFetchTag = new ConcurrentHashMap<>();
	//прокрутки, открытые на одном запросе -- этот запрос надо закрыть после закрытия всех прокруток
	private final Map<Statement, Set<Fetch>> fetchesOnStatements = new ConcurrentHashMap<>();
	
	/**
	 * Поиск открытой прокрутки.
	 * Находит прокрутку, открытую заданным пользователем, имеющую
	 * заданный номер и находящуюся на заданной позиции.
	 * @param logon Сессия пользователя.
	 * @param queryTag номер запроса у пользователя.
	 * @param startPos текущая позиция в прокрутке.
	 * @return Прокрутка, соответствующая параметрам. null, если таковой нет.
	 */
	public Fetch searchOpenedFetch(UserLogon logon, int queryTag, int startPos) {
		String token = logon.getToken();
		Map<Integer, Fetch> userFetches = fetchesForUsers.get(token);
		if (userFetches == null) {
			return null;
		}
		Fetch fetch = userFetches.get(queryTag);
		if (fetch == null) {
			return null;
		}
		if (fetch.currentPosition == startPos) {
			return fetch;
		} else {
			return null;
		}
	}

	/**
	 * Сохранение открытой прокрутки.
	 * Прокрутка сохраняется в памяти для осуществления фетчинга без повторного
	 * обращения к базе данных.
	 * 
	 * @param fetch сохраняемая прокрутка.
	 * @param logon сессия пользователя, под которой был сделан запрос.
	 * @return номер сохранённой прокрутки (для повторного обращения).
	 * @throws CarabiException если пользователь сохранил более {@link Settings#FETCHES_BY_USER} прокруток
	 */
	public synchronized int saveFetch(Fetch fetch, UserLogon logon) throws CarabiException {
		String userToken = logon.getToken();
		Map<Integer, Fetch> userFetches;
		//достаём/создаём прокрутки текущего пользователя
		int tag; //номер сохраняемой прокрутки
		if (fetchesForUsers.containsKey(userToken)) {
			userFetches = fetchesForUsers.get(userToken);
			tag = usersFetchTag.get(userToken);
		} else {
			userFetches = new ConcurrentHashMap<>();
			fetchesForUsers.put(userToken, userFetches);
			tag = 0;
		}
		//Если пользователь израсходовал лимит -- ошибка
		if (userFetches.size() + 1 > Settings.FETCHES_BY_USER) {
			throw new CarabiException("User " + logon.userLogin() + " saved too many opened fetches.", Settings.OPENED_FETCHES_LIMIT_ERROR);
		}
		//Сохраняем прокрутку в пользовательской коллекции
		userFetches.put(tag, fetch);
		usersFetchTag.put(userToken, tag + 1);
		//Сохраняем прокрутку в коллекции её запроса
		Set<Fetch> statementsFetches;
		if (!fetchesOnStatements.containsKey(fetch.statement)) {
			statementsFetches = new HashSet<>();
			fetchesOnStatements.put(fetch.statement, statementsFetches);
		} else {
			statementsFetches = fetchesOnStatements.get(fetch.statement);
		}
		statementsFetches.add(fetch);
		return tag;
	}
	
	/**
	 * Перемещение по прокрутке.
	 * Выборка порции данных из прокрутки, созданной SQL или XML-запросом.
	 * @param logon сессия пользователя, под которой открыта прокрутка
	 * @param queryTag Номер прокрутки у пользователя
	 * @param startPos
	 * @param fetchCount число считываемых записей
	 * @param list Полученные данные
	 * @param endpos Позиция в прокрутке после выборки
	 * @return Количество возвращённых записей
	 */
	public int fetchNext(UserLogon logon,
			int queryTag,
			int startPos,
			int fetchCount,
			Holder<ArrayList<ArrayList<?>>> list,
			Holder<Integer> endpos
		) throws SQLException {
		Fetch fetch = searchOpenedFetch(logon, queryTag, startPos);
		if (fetch == null) {
			return Settings.SQL_EOF;
		}
		list.value = fetch.processFetching(fetchCount);
		int size = list.value.size();
		endpos.value = startPos + size;
		if (size < fetchCount) {
			closeFetch(logon, queryTag);
		}
		return size;
	}

	/**
	 * Закрытие прокрутки.
	 * Закрывает прокрутку (курсор и запрос, если на нём нет других открытых прокруток).<br/>
	 * Прокрутку следует закрыть вручную, если не были выкачаны все данные.
	 * При полном выкачивании закрытие происходит автоматически.
	 * 
	 * @param logon сессия пользователя, под которой был сделан запрос.
	 * @param queryTag номер сохранённой прокрутки.
	 * @throws SQLException 
	 */
	public void closeFetch(UserLogon logon, int queryTag)  throws SQLException {
		String userToken = logon.getToken();
		if (!fetchesForUsers.containsKey(userToken)) {
			return;
		}
		Map<Integer, Fetch> userFetches = fetchesForUsers.get(userToken);
		//Прокрутка могла закрыться сама при исчерпании данных
		if (!userFetches.containsKey(queryTag)) {
			return;
		}
		//Удаляем прокрутку из пользовательской коллекции и закрываем её курсор
		Fetch closingFetch = userFetches.remove(queryTag);
		closingFetch.cursor.close();
		//Удаляем прокрутку из списка открытых на данном запросе, если он пуст -- закрываем запрос
		Set<Fetch> statementFetches = fetchesOnStatements.get(closingFetch.statement);
		statementFetches.remove(closingFetch);
		if (statementFetches.isEmpty()) {
			closingFetch.statement.close();
			Logger.getLogger(this.getClass().getName()).info("Statement closed");
		}
	}

	/**
	 * Закрытие всех пользовательских прокруток.
	 * @param logon закрываемая сессия
	 * @throws SQLException 
	 */
	public void closeAllFetches(UserLogon logon) throws SQLException {
		String userToken = logon.getToken();
		if (!fetchesForUsers.containsKey(userToken)) {
			return;
		}
		Statement statement = null;
		Map<Integer, Fetch> userFetches = fetchesForUsers.get(userToken);
		for (Fetch fetch: userFetches.values()) {
			fetch.cursor.close();
			statement = fetch.statement;
		}
		if (statement != null) {
			fetchesOnStatements.remove(statement);
			statement.close();
		}
		fetchesForUsers.remove(userToken);
	}
}
