package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 *
 * @author sasha
 */
@Entity
@Table(name="USER_STATUS")
@NamedQuery(name = "getUserStatus", query = "select US from UserStatus US where US.sysname = :sysname")
public class UserStatus implements Serializable {

	@Id
	@Column(name="STATUS_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	@Column(name="NAME")
	String name;
	@Column(name="SYSNAME")
	String sysname;
	@Column(name="DESCRIPTION")
	String description;
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
	}
	
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
	
	public String getDescription() {
		return description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}
}
