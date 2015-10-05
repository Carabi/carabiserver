package ru.carabi.server;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.persistence.EntityManager;
import org.apache.commons.lang3.StringUtils;

/**
 * Типовые операции с JPA-сущностями.
 * @param <E> Тип entity
 * @param <K> Тип ключа
 * @author sasha
 */
public class EntityManagerTool<E, K> {
	
	/**
	 * Найти сущность по ключу или создать, если её нет в базе.
	 * 
	 * @param em текущий {@link  javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param key объект ключа в том виде, в котором его использует EntityManager
	 * @return 
	 */
	public E createOrFind(EntityManager em, Class<E> eType, K key) {
		E entity = null;
		try {
			if (key == null) {
				entity = eType.newInstance();
			} else {
				entity = em.find(eType, key);
				if (entity == null) {
					entity = eType.newInstance();
				}
			}
		} catch (InstantiationException | IllegalAccessException ex) {
			Logger.getLogger(EntityManagerTool.class.getName()).log(Level.SEVERE, null, ex);
		}
		return entity;
	}
	
	/**
	 * Найти сущность по ключу или создать, если её нет в базе.
	 * @param em
	 * @param eType
	 * @param keyType
	 * @param keyStr
	 * @return
	 * @throws CarabiException 
	 */
	
	public E createOrFind (EntityManager em, Class<E> eType, Class<K> keyType,String keyStr) throws CarabiException{
		if (StringUtils.isEmpty(keyStr)) {
			return createOrFind(em, eType, null);
		}
		Object key;
		try {
			switch (keyType.getSimpleName()) {
				case "Long" :
					key = Long.decode(keyStr);
					break;
				case "Integer" :
					key = Integer.decode(keyStr);
					break;
				case "Short" :
					key = Short.decode(keyStr);
					break;
				default:
					final CarabiException e = new CarabiException("Unknown ID type. Known are: Long, Integer, Short.");
					Logger.getLogger(this.getClass().getCanonicalName()).log(Level.WARNING, "" , e);
					throw e;
			}
		} catch (NumberFormatException nfe) {
			final CarabiException e = new CarabiException(
					"Incorrect ID format. "
					+ keyType.getCanonicalName() + " wanted", nfe);
			Logger.getLogger(this.getClass().getCanonicalName()).log(Level.WARNING, "" , e);
			throw e;
		}
		return createOrFind(em, eType, (K) key);
	}
}
