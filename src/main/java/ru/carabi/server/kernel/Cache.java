package ru.carabi.server.kernel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.ejb.Singleton;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author sasha
 */
@Singleton
public class Cache<T> {
	private static final String DELIMITER = "|";
	private final Map<String, Object> cache = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> usersKeys = new ConcurrentHashMap<>();
	
	
	public void put(String token, String key, Object value) {
		Set<String> userKeys = usersKeys.get(token);
		if (userKeys == null) {
			userKeys = new HashSet<>();
			usersKeys.put(token, userKeys);
		}
		userKeys.add(key);
		cache.put(key, value);
	}
	public void put(String token, String[] keys, Object value) {
		put(token, StringUtils.join(keys, DELIMITER), value);
	}
	
	public T get(String key) {
		return (T) cache.get(key);
	}
	
	public T get(String[] keys) {
		return get(StringUtils.join(keys, DELIMITER));
	}
	
	public void removeUserData(String token) {
		Set<String> userKeys = usersKeys.get(token);
		if (userKeys == null) {
			return;
		}
		for (String key: userKeys) {
			cache.remove(key);
		}
	}
}
