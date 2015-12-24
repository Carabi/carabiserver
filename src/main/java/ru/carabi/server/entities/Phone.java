package ru.carabi.server.entities;

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
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import ru.carabi.server.logging.CarabiLogging;

/**
 * Телефон пользователя.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="PHONE")
@NamedQueries ({
	@NamedQuery(name="selectUserPhones",
		query="select p from Phone p where p.owner.id = :ownerId")
})
public class Phone extends AbstractEntity {
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
		switch (split.length) {
			case 4:
				countryCode = "".equals(split[0]) ? null : Integer.valueOf(split[0]);
				regionCode = "".equals(split[1]) ? null : Integer.valueOf(split[1]);
				mainNumber = "".equals(split[2]) ? null : Long.valueOf(split[2]);
				suffix = "".equals(split[3]) ? null : Integer.valueOf(split[3]);
			break;
			case 3:
				countryCode = "".equals(split[0]) ? null : Integer.valueOf(split[0]);
				regionCode = "".equals(split[1]) ? null : Integer.valueOf(split[1]);
				mainNumber = "".equals(split[2]) ? null : Long.valueOf(split[2]);
				suffix = null;
			break;
			case 2:
				countryCode = null;
				regionCode = "".equals(split[0]) ? null : Integer.valueOf(split[0]);
				mainNumber = "".equals(split[1]) ? null : Long.valueOf(split[1]);
				suffix = null;
			break;
			case 1:
				countryCode = null;
				regionCode = null;
				mainNumber = "".equals(split[0]) ? null : Long.valueOf(split[0]);
				suffix = null;
			break;
			case 0:
				logger.log(Level.WARNING, "0 segments in phone number numbers. Column 'MAIN_NUMBER' won't accept a NULL value.");
				throw new IllegalArgumentException("0 segments in phone number numbers. Column 'MAIN_NUMBER' won't accept a NULL value.");
			default:
				logger.log(Level.WARNING, "too many segments in phone number numbers. maximum is four: country code, region code, main number and suffix");
				throw new IllegalArgumentException("too many segments in phone number numbers. maximum is four: country code, region code, main number and suffix");
		}
	}
	
	@Override
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

}
