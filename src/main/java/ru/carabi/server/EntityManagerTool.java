package ru.carabi.server;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.entities.AbstractEntity;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Типовые операции с JPA-сущностями.
 * @author sasha<kopilov.ad@gmail.com>
 */
public class EntityManagerTool {
	private static final Logger logger = CarabiLogging.getLogger(EntityManagerTool.class);
	
	/**
	 * Найти сущность по ключу или создать, если её нет в базе.
	 * 
	 * @param <E> Тип entity
	 * @param <K> Тип ключа
	 * @param em текущий {@link javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param key объект ключа в том виде, в котором его использует EntityManager
	 * @return новый или найденный экземпляр сущности
	 */
	public static <E extends AbstractEntity, K extends Object> E createOrFind(EntityManager em, Class<E> eType, K key) {
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
			logger.log(Level.SEVERE, null, ex);
		}
		return entity;
	}
	
	/**
	 * Найти сущность по строковому представлению ключа или создать, если её нет в базе.
	 * Поддерживаются ключи Long, Integer, Short.
	 * @param <E> Тип entity
	 * @param <K> Тип ключа
	 * @param em текущий {@link javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param keyType объект Class для оперируемого ключа
	 * @param keyStr
	 * @return новый или найденный экземпляр сущности
	 * @throws CarabiException  неподдерживаемый тип первичного ключа.
	 */
	
	public static <E extends AbstractEntity, K extends Object> E createOrFind(EntityManager em, Class<E> eType, Class<K> keyType,String keyStr) throws CarabiException{
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
					logger.log(Level.WARNING, "" , e);
					throw e;
			}
		} catch (NumberFormatException nfe) {
			final CarabiException e = new CarabiException(
					"Incorrect ID format. "
					+ keyType.getCanonicalName() + " wanted", nfe);
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
		return createOrFind(em, eType, (K) key);
	}
	
	/**
	 * Найти список сущностей по произвольному текстовому полю
	 * @param <E> Тип entity
	 * @param em текущий {@link javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param fieldName имя поля, по которому ищем
	 * @param fieldValue значение поля, по которому ищем
	 * @return список найденных entity с fieldName == fieldValue
	 */
	public static <E extends AbstractEntity> List<E> findByStrField(EntityManager em, Class<E> eType, String fieldName, String fieldValue) {
		TypedQuery<E> query = em.createQuery("select E from " + eType.getCanonicalName() + " E where E."+fieldName + " = :fieldValue", eType);
		query.setParameter("fieldValue", fieldValue);
		return query.getResultList();
	}
	
	/**
	 * Найти сущность по произвольному текстовому полю
	 * @param <E> Тип entity
	 * @param em текущий {@link javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param fieldName имя поля, по которому ищем
	 * @param fieldValue значение поля, по которому ищем
	 * @return entity с fieldName == fieldValue или null, если не найдено
	 * @throws IllegalStateException если найдено несколько объектов
	 */
	public static <E extends AbstractEntity> E findByStrFieldSingle(EntityManager em, Class<E> eType, String fieldName, String fieldValue) {
		List<E> found = findByStrField(em, eType, fieldName, fieldValue);
		switch (found.size()) {
			case 0:
				return null;
			case 1:
				return found.get(0);
			default:
				throw new IllegalStateException("Search "+ eType.getCanonicalName() + " by " + fieldName + " == " + fieldValue + " - found " + found.size() + " instead of single");
		}
	}
	
	/**
	 * Поиск entity по полю sysname.
	 * @param <E> Тип entity
	 * @param em текущий {@link javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param fieldValue значение поля sysname
	 * @return найденный Entity, null, если не нашли.
	 */
	public static <E extends AbstractEntity> E findBySysname(EntityManager em, Class<E> eType, String fieldValue) {
		return findByStrFieldSingle(em, eType, "sysname", fieldValue);
	}
	/**
	 * Поиск entity по полю sysname с созданием нового при отсутствии.
	 * @param <E> Тип entity
	 * @param em текущий {@link javax.persistence.EntityManager}
	 * @param eType объект Class для оперируемого Entity
	 * @param fieldValue значение поля sysname
	 * @return найденный Entity, новый объект, если не нашли.
	 */
	public static <E extends AbstractEntity> E findBySysnameOrCreate(EntityManager em, Class<E> eType, String fieldValue) {
		E entity = findBySysname(em, eType, fieldValue);
		if (entity == null) {
			try {
				entity = eType.newInstance();
			} catch (InstantiationException | IllegalAccessException ex) {
				Logger.getLogger(EntityManagerTool.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return entity;
	}
}
