/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package ru.carabi.server.kernel;

import java.sql.Connection;
import java.sql.SQLException;
import javax.ejb.Stateless;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import ru.carabi.server.CarabiException;
import ru.carabi.server.OracleConnectionError;
import ru.carabi.server.Settings;

/**
 *
 * @author sasha
 */
@Stateless
public class ConnectorBean {
	/**
	 * Получить подключение к Oracle.
	 * экспериментально установлено, что при разрыве XA-транзакции получать подключение
	 * повторно бесполезно, но выкидывание RuntimeException необъяснимым образом
	 * позволяет не падать SOAP-вызову
	 * @param jndi имя ресурса
	 * @param crash при повторной попытке получить подключение -- кинуть RuntimeException
	 * @return
	 * @throws CarabiException
	 * @throws NamingException
	 * @throws SQLException 
	 */
	public Connection getConnection(String jndi, boolean crash) throws CarabiException, NamingException, SQLException {
		if (crash) {
			throw new RuntimeException("test");
		}
		Context ctx = new InitialContext();
		DataSource dataSource = (DataSource)ctx.lookup(jndi);
		if (dataSource == null) {
			throw new OracleConnectionError("No such pool: " + jndi);
		}
		return dataSource.getConnection();
	}
}
