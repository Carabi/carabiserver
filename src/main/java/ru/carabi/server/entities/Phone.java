package ru.carabi.server.entities;

import com.ctc.wstx.util.StringUtil;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;
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
import org.apache.commons.lang3.StringUtils;
import ru.carabi.server.logging.CarabiLogging;

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
	
	private int ordernumber;
	@Column(name="COUNTRY_CODE")
	private Integer countryCode;
	@Column(name="REGION_CODE")
	private Integer regionCode;
	@Column(name="MAIN_NUMBER")
	private long mainNumber;
	@Column(name="SUFFIX")
	private Integer suffix;
	
	@ManyToOne
	@JoinColumn(name="SCHEMA_ID")
	private ConnectionSchema sipSchema;
	
	/**
	 * Создание телефона парсингом строки вида код1^код2^номер^добавочный
	 * @param phoneStr 
	 */
	public void parse (String phoneStr) {
		Logger logger = CarabiLogging.getLogger(this);
		logger.info(phoneStr);
		String[] split = phoneStr.split("\\^");
		logger.log(Level.INFO, "split.length: {0}", split.length);
		for (String s: split) {
			logger.log(Level.INFO, s);
		}
		try {
			switch (split.length) {
				case 4:
					countryCode = Integer.valueOf(split[0]);
					regionCode = Integer.valueOf(split[1]);
					mainNumber = Integer.valueOf(split[2]);
					if (!StringUtils.isEmpty(split[3])) {
						suffix = Integer.valueOf(split[3]);
					}
				break;
				case 3:
					countryCode = Integer.valueOf(split[0]);
					regionCode = Integer.valueOf(split[1]);
					mainNumber = Integer.valueOf(split[2]);
				break;
				case 2:
					regionCode = Integer.valueOf(split[0]);
					mainNumber = Integer.valueOf(split[1]);
				break;
				case 1:
					mainNumber = Integer.valueOf(split[0]);
				break;
				default:
					throw new IllegalArgumentException("too many segments");
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Can not parse segment as number", e);
		}
	}
	
	public String toString() {
		return countryCode + "^" + regionCode + "^" + mainNumber + "^" + suffix;
	}
	
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
	
	public int getOrdernumber() {
		return ordernumber;
	}
	
	public void setOrdernumber(int orderby) {
		this.ordernumber = orderby;
	}
	
	public CarabiUser getOwner() {
		return owner;
	}
	
	public void setOwner(CarabiUser owner) {
		this.owner = owner;
	}
	
	public Integer getCountryCode() {
		return countryCode;
	}
	
	public void setCountryCode(Integer countryCode) {
		this.countryCode = countryCode;
	}
	
	public Integer getRegionCode() {
		return regionCode;
	}
	
	public void setRegionCode(Integer regionCode) {
		this.regionCode = regionCode;
	}
	
	public long getMainNumber() {
		return mainNumber;
	}
	
	public void setMainNumber(long mainNumber) {
		this.mainNumber = mainNumber;
	}
	
	public Integer getSuffix() {
		return suffix;
	}
	
	public void setSuffix(Integer suffix) {
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
	
}
