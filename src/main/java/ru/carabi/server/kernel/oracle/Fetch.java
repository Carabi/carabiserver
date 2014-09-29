package ru.carabi.server.kernel.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.logging.Logger;
import ru.carabi.server.Settings;
import ru.carabi.server.Utls;

/**
 * Прокручиваемый запрос.
 * Включает курсор с текущей позицией,
 * обращение к базе (для закрытия после завершения),
 * список полей
 */
public class Fetch {
	public ResultSet cursor;
	public Statement statement;//Открытое SQL-обращение -- должно быть закрыто вместе с курсором.
	public int currentPosition;
	private Integer recordCount = null;
	public ArrayList<ArrayList<String>> columns;
	
	public Fetch(ResultSet cursor, Statement statement, int startpos) throws SQLException {
		this.cursor = cursor;
		this.statement = statement;
		this. currentPosition = startpos;
		postConstruct();
	}
	
	public Fetch(Connection connection, String sql, int startpos) throws SQLException {
		PreparedStatement statement = connection.prepareStatement(sql);
		cursor = statement.executeQuery();
		this.statement = statement;
		this.currentPosition = startpos;
		postConstruct();
	}
	
	public Fetch(Connection connection, String sql) throws SQLException {
		this(connection, sql, 0);
	}
	
	private void postConstruct() throws SQLException {
		columns = Utls.getResultSetColumns(cursor);
		//Если запрос открыт с середины -- крутим
		for (int i=0; i<currentPosition; i++) {
			if (!this.cursor.next()) {
				break;
			}
		}
	}
	
	ArrayList<ArrayList<?>> processFetching(int fetchCount) throws SQLException {
		if (cursor.isClosed()) {//Курсор уже был закрыт (данные закончились),
			//но клиент может обратиться к этой прокрутке ещё раз
			return new ArrayList<>(0);
		}
		ArrayList<ArrayList<?>> result;
		if (fetchCount <= Settings.MAX_FETCH_SIZE) {
			result= new ArrayList<>(fetchCount);
		} else {//fetchCount может быть задан очень большим, чтобы гарантированно получить все строки
			result= new ArrayList<>();
		}
		for (int i=0; i<fetchCount; i++) {//Берём из курсора указанное число строк,
			//если их там достаточно
			if (cursor.next()) {
				LinkedHashMap<String, ?> rowMap = Utls.fetchRow(cursor);
				result.add(new ArrayList<>(rowMap.values()));
				currentPosition++;
			} else {
				cursor.close();
				break;
			}
		}
		return result;
	}

	public Integer getRecordCount() {
		return recordCount;
	}

	public void setRecordCount(Integer recordCount) {
		this.recordCount = recordCount;
	}
}
