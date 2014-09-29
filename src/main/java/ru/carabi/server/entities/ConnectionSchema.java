package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 * Формализация подключения к Oracle.
 * Включает ID, название и JNDI-имя пула или параметры для прямого JDBC-подключения.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@NamedQueries({
	@NamedQuery(name="selectAllSchemas",
		query="SELECT C.id, c.name FROM ConnectionSchema C order by C.id"),
	@NamedQuery(name="selectDefaultSchema",
		query="SELECT C.id, C.jndi, C.name from ConnectionSchema C WHERE C.id IN ( \n" +
"	SELECT MIN(CS.id) FROM ConnectionSchema CS \n" +
")"),
	@NamedQuery(name="findSchemaBySysname",
		query="SELECT C from ConnectionSchema C WHERE C.sysname = :sysname"
	),
	@NamedQuery(name="fullSelectAllSchemas",
		query="SELECT C FROM ConnectionSchema C order by C.id")
})
@Table(name="CONNECTION_SCHEMA")
public class ConnectionSchema implements Serializable {
	private static final long serialVersionUID = 4L;
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="SCHEMA_ID")
	private Integer id;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}


	@Override
	public int hashCode() {
		int hash = 0;
		hash += (id != null ? id.hashCode() : 0);
		return hash;
	}
	
	@Override
	public boolean equals(Object object) {
		// TODO: Warning - this method won't work in the case the id fields are not set
		if (!(object instanceof ConnectionSchema)) {
			return false;
		}
		ConnectionSchema other = (ConnectionSchema) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(super.toString());
		sb.append("[id=");
		sb.append(String.valueOf(id));
		sb.append(", name=");
		sb.append(String.valueOf(getName()));
		sb.append(", sysname=");
		sb.append(String.valueOf(getSysname()));
		sb.append(", jndi=");
		sb.append(String.valueOf(getJNDI()));
		sb.append("]");
		return sb.toString();
	}
	
	/**
	 * Название базы данных
	 */
	private String name;
	/**
	 * Системное имя (для Carabi-клиента)
	 */
	private String sysname;
	/**
	 * JNDI-имя пула
	 */
	private String jndi;
	
	/**
	 * Адрес СУБД (если не используется пул)
	 */
	private String address;
	
	/**
	 * логин в СУБД (если не используется пул)
	 */
	private String login;
	
	/**
	 * пароль в СУБД (если не используется пул)
	 */
	private String password;
	
	private String description;
	
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSysname() {
		return sysname;
	}

	public void setSysname(String sysname) {
		this.sysname = sysname;
	}

	public String getJNDI() {
		return jndi;
	}

	public void setJNDI(String jndi) {
		this.jndi = jndi;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getLogin() {
		return login;
	}

	public void setLogin(String login) {
		this.login = login;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}
}
