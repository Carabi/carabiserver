package ru.carabi.server.kernel.oracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.carabi.server.Utls;

/**
 * Параметр SQL-запроса.
 * Применяется при запуске заросов с параметрами.
 * @author sasha
 */
public class QueryParameter {
	private String name;//системное наименование в запросе
	private String value;//приведённое значение параметра. Строка, дата или число -- в виде строки, курсор (на выходе) -- номер сохранённой прокрутки.
	private Object valueObject;//Исходное значение параметра.
	                           //Для курсоров: на этапе обработки -- ResultSet, на выходе -- шапка и масссив строк, помещённые в HashMap
	private String type;//название типа в Oracle
	private Integer isIn;//является входным (0 -- нет, 1 -- да)
	private Integer isOut;//является выходным (0 -- нет, 1 -- да)
	private Integer isNull = 0;
	public String getName() {
		return name;
	}
	
	public QueryParameter(String name, String value) {
		this.name = name;
		this.value = value;
		this.type= "VARCHAR";
	}
	
	public QueryParameter(String name, long value) {
		this.name = name;
		this.value = Long.toString(value);
		this.type= "NUMBER";
	}
	
	public void setName(String name) {
		this.name = name;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public Object getValueObject() {
		return valueObject;
	}

	public void setValueObject(Object valueObject) {
		this.valueObject = valueObject;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public Integer getIsIn() {
		return isIn;
	}

	public void setIsIn(Integer isIn) {
		this.isIn = isIn;
	}

	public Integer getIsOut() {
		return isOut;
	}

	public void setIsOut(Integer isOut) {
		this.isOut = isOut;
	}

	public Integer getIsNull() {
		return isNull;
	}

	public void setIsNull(Integer isNull) {
		this.isNull = isNull;
	}
	
	public static class Type {
		public static String NUMBER = "NUMBER";
	}
	
	public static final Integer TRUE = 1;
	public static final Integer FALSE = 0;
	
	public Map<String, ArrayList<ArrayList<?>>> getCursorValueRaw() {
		try {
			return (Map<String, ArrayList<ArrayList<?>>>)valueObject;
		} catch (ClassCastException e) {
			throw new IllegalStateException("There is no cursor, sorry!", e);
		}
	}
	
	public ArrayList<LinkedHashMap<String, ?>> getCursorRedim() {
		return Utls.redim(getCursorValueRaw());
	}
}
