package ru.carabi.server.soap;

import javax.ejb.EJB;
import javax.jws.WebService;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import ru.carabi.server.kernel.MonitorBean;

/**
 * Функции для контроля за состоянием сервера и баз данных
 * @author sasha
 */
@WebService(serviceName = "MonitorService")
public class MonitorService {
	
	@EJB
	private MonitorBean monitor;
	/**
	 * Простейшая функция для проверки доступности сервиса
	 */
	@WebMethod(operationName = "hello")
	public String hello(@WebParam(name = "name") String name) {
		return "Hello " + name + " !";
	}
	
	/**
	 * Получение числа блокировок в ядровой базе.
	 * И просто проверка, что она доступна.
	 * @return количество блокировок в ядровой базе
	 */
	@WebMethod(operationName = "getKernelDBLockcount")
	public int getKernelDBLockcount() {
		return monitor.getKernelDBLockcount();
	}
}
