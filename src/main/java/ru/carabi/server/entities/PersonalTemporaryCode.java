package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;

/**
 * Временные коды (например, ждя восстановления пароля)
 * @author sasha
 */
@Entity
@Table(name="PERSONAL_TEMPORARY_CODE")
public class PersonalTemporaryCode implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="TEMPORARY_CODE")
	private String code;
	
	@ManyToOne
	@JoinColumn(name="USER_ID")
	private CarabiUser user;
	
	@Column(name="EXPIRATION_DATE")
	@Temporal(javax.persistence.TemporalType.DATE)
	private Date timestamp;
	
	@Column(name="CODE_TYPE")
	private String codeType;
	
	public String getCode() {
		return code;
	}
	
	public void setCode(String code) {
		this.code = code;
	}
	
	public CarabiUser getUser() {
		return user;
	}
	
	public void setUser(CarabiUser user) {
		this.user = user;
	}
	
	public Date getTimestamp() {
		return timestamp;
	}
	
	public void setTimestamp(Date timestamp) {
		this.timestamp = timestamp;
	}
	
	public String getCodeType() {
		return codeType;
	}
	
	public void setCodeType(String codeType) {
		this.codeType = codeType;
	}
}
