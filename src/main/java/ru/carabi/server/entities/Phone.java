package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 *
 * @author sasha
 */
@Entity
@Table(name="PHONE")
@NamedQueries ({
})
public class Phone implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="PHONE_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@ManyToOne
	@JoinColumn(name="PHONE_TYPE")
	private PhoneType phoneType;
	
	@ManyToOne
	@JoinColumn(name="OWNER_ID")
	private CarabiUser owner;
	
	@Column(name="COUNTRY_CODE")
	private int countryCode;
	@Column(name="REGION_CODE")
	private int regionCode;
	@Column(name="MAIN_NUMBER")
	private long mainNumber;
	@Column(name="SUFFIX")
	private int suffix;
	
	@ManyToOne
	@JoinColumn(name="SCHEMA_ID")
	private ConnectionSchema sipSchema;
	
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
		this.id = id;
	}
	
	public PhoneType getPhoneType() {
		return phoneType;
	}
	
	public void setPhoneType(PhoneType phoneType) {
		this.phoneType = phoneType;
	}
	
	public CarabiUser getOwner() {
		return owner;
	}
	
	public void setOwner(CarabiUser owner) {
		this.owner = owner;
	}
	
	public int getCountryCode() {
		return countryCode;
	}
	
	public void setCountryCode(int countryCode) {
		this.countryCode = countryCode;
	}
	
	public int getRegionCode() {
		return regionCode;
	}
	
	public void setRegionCode(int regionCode) {
		this.regionCode = regionCode;
	}
	
	public long getMainNumber() {
		return mainNumber;
	}
	
	public void setMainNumber(long mainNumber) {
		this.mainNumber = mainNumber;
	}
	
	public int getSuffix() {
		return suffix;
	}
	
	public void setSuffix(int suffix) {
		this.suffix = suffix;
	}
	
	public ConnectionSchema getSipSchema() {
		return sipSchema;
	}
	
	public void setSipSchema(ConnectionSchema sipSchema) {
		this.sipSchema = sipSchema;
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
		Phone other = (Phone) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}
	
	@Override
	public String toString() {
		return "+" + countryCode + "-" + regionCode + "-" + mainNumber;
	}
}
