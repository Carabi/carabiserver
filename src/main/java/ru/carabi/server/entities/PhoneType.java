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
 *
 * @author sasha
 */
@Entity
@Table(name="PHONE_TYPE")
@NamedQueries ({
	@NamedQuery(name = "findPhoneType",	query = "select PT from PhoneType PT where PT.name = :name or PT.sysname = :name"),
	@NamedQuery(name = "selectAllPhoneTypes", query = "select PT from PhoneType PT")
})
public class PhoneType implements Serializable {
	//typical sysnames
	public static final String SIP = "SIP";
	public static final String MOBILE = "mobile";
	public static final String SIMPLE = "simple";

	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="PHONE_TYPE_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String name;
	private String sysname;
	
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
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
	
	@Override
	public int hashCode() {
		int hash = 0;
		hash += (id != null ? id.hashCode() : 0);
		return hash;
	}
	
	@Override
	public boolean equals(Object object) {
		// TODO: Warning - this method won't work in the case the id fields are not set
		if (!(object instanceof CarabiUser)) {
			return false;
		}
		PhoneType other = (PhoneType) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}
}
