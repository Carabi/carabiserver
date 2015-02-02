package ru.carabi.server.kernel;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

/**
 *
 * @author sasha
 */
@Stateless
public class MonitorBean {
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	EntityManager em;
	
	public int getDerbyLockcount() {
		Query locksCountQuery = em.createNativeQuery("select count(*) from SYSCS_DIAG.LOCK_TABLE");
		Object result = locksCountQuery.getSingleResult();
		return ((Number)result).intValue();
	}
}
