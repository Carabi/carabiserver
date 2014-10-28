package ru.carabi.server.kernel.oracle;

import java.sql.Timestamp;
import java.text.ParseException;
import java.util.regex.Pattern;
import me.lima.ThreadSafeDateParser;
/**
 * Обёртка java.sql.Timestamp.
 * Для представления даты в стандартном для Carabi текстовом формате
 * DD.MM.YYYY^HH:MM:SS
 * @author sasha
 */
public class CarabiDate extends Timestamp {
	
	public static final String pattern = "dd.MM.yyyy^HH:mm:ss";
	public static final String patternShort = "dd.MM.yyyy";
	public static final Pattern patternValidate = Pattern.compile("\\d\\d\\.\\d\\d\\.\\d\\d\\d\\d\\^\\d\\d:\\d\\d:\\d\\d");
	public static final Pattern patternValidateShort = Pattern.compile("\\d\\d\\.\\d\\d\\.\\d\\d\\d\\d");
	
	public CarabiDate() {
		super(new java.util.Date().getTime());
	}
	public CarabiDate(java.util.Date date) {
		super(date.getTime());
	}
	public CarabiDate(Timestamp timestamp) {
		super(timestamp.getTime());
	}
	public CarabiDate(long time) {
		super(time);
	}
	public CarabiDate(String date) throws ParseException {
		super(parseCarabiDate(date));
	}
	
	public static long parseCarabiDate(String date) throws ParseException {
		if (patternValidate.matcher(date).matches()) {
			return ThreadSafeDateParser.parseLongDate(date, pattern);
		} else if (patternValidateShort.matcher(date).matches()) {
			return ThreadSafeDateParser.parseLongDate(date + "^00:00:00", pattern);
		} else {
			throw new IllegalArgumentException("Not correct CarabiDate: " + date);
		}
	}
	
	public static CarabiDate wrap(java.util.Date date) {
		if (date == null) {
			return null;
		} else {
			return new CarabiDate(date);
		}
	}
	
	@Override
	public String toString() {
		return ThreadSafeDateParser.format(this, pattern);
	}
	
	public String toJsonString() {
		return toString();
	}
}
