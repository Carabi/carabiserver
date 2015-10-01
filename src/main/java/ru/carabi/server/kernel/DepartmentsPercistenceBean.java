package ru.carabi.server.kernel;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import ru.carabi.server.CarabiException;
import ru.carabi.server.UserLogon;
import ru.carabi.server.entities.CarabiUser;
import ru.carabi.server.entities.Department;
import ru.carabi.server.logging.CarabiLogging;

/**
 *
 * @author sasha
 */
@Stateless
public class DepartmentsPercistenceBean {
	
	private static final Logger logger = CarabiLogging.getLogger(DepartmentsPercistenceBean.class);
	
	@PersistenceContext(unitName = "ru.carabi.server_carabiserver-kernel")
	private EntityManager em;
	
	/**
	 * Поиск подразделения по кодовому названию
	 * @param sysname кодовое название подразделения ({@link Department#sysname})
	 * @return найденное подразделение, null, если не найдено
	 */
	public Department findDepartment(String sysname) throws CarabiException {
		try {
			TypedQuery<Department> getDepartmentInfo = em.createNamedQuery("getDepartmantInfo", Department.class);
			getDepartmentInfo.setParameter("sysname", sysname);
			Department department = getDepartmentInfo.getSingleResult();
			return department;
		} catch (NoResultException ex) {
			return null;
		} catch (NonUniqueResultException ex) {
			final CarabiException e = new CarabiException("Wore than one department with sysname " + sysname);
			logger.log(Level.WARNING, "" , e);
			throw e;
		}
	}
	
	/**
	 * Поиск подразделения по кодовому названию
	 * @param sysname кодовое название подразделения ({@link Department#sysname})
	 * @return найденное подразделение
	 * @throws CarabiException если нет такого подразделения
	 */
	public Department getDepartment(String sysname) throws CarabiException {
		Department department = findDepartment(sysname);
		if (department == null) {
			final CarabiException e = new CarabiException("No department with sysname " + sysname);
			logger.log(Level.WARNING, "" , e);
			throw e;
		} else {
			return department;
		}
	}
	
	/**
	 * Получение всех подразделений для текущего пользователя от основного и выше
	 * @param userLogon
	 * @return 
	 */
	public List<Department> getDepartmentBranch(UserLogon userLogon) {
		String sql = "select * from appl_department.get_departments_branch_detailed(?, null)";
		Query query = em.createNativeQuery(sql);
		query.setParameter(1, userLogon.getToken());
		List resultList = query.getResultList();
		int i = resultList.size();
		Department[] result = new Department[i];
		for (Object row: resultList) {
			i--;
			Object[] data = (Object[])row;
			Department department = new Department();
			department.setId((Integer) data[0]);
			department.setName((String) data[1]);
			department.setSysname((String) data[2]);
			department.setDescription((String) data[3]);
			result[i] = department;
		}
		return Arrays.asList(result);
	}
	
	/**
	 * Проверка, что пользователь имеет доступ к подразделению.
	 * Для этого у подразделения пользователя (основного или дополнительного) и у указанного подразделения
	 * должен иметься общий предок в иерархии (компания).
	 * @param logon
	 * @param department
	 * @return 
	 */
	public boolean isDepartmentAvailable(UserLogon logon, Department department) {
		return true;//TODO: write implementation
	}
}
