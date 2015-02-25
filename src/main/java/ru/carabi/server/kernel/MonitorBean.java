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
	
	public int getKernelDBLockcount() {
		String sql = "SELECT count(*)\n" +
			"FROM pg_locks AS locks \n" +
			"	LEFT JOIN pg_database AS base ON locks.database= base.oid \n" +
			"	LEFT JOIN pg_class AS classes ON locks.relation = classes.oid\n" +
			"where locks.database is not null and base.oid is not null\n" +
			"	and relname not like 'pg_%'";
		Query locksCountQuery = em.createNativeQuery(sql);
		Object result = locksCountQuery.getSingleResult();
		return ((Number)result).intValue();
	}
}
