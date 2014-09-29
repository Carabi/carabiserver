package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Transient;

/**
 * Параметр SQL-запроса.
 * Применяется при запуске заросов с параметрами.
 * @author sasha
 */
@Entity
@Table(name="ORACLE_PARAMETER")
public class QueryParameterEntity implements Serializable {

	@Id
	@Column(name="PARAMETER_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String name;
	@Transient
	private String value;
	@Column(name="TYPE_NAME")
	private String type;
	@Column(name="IS_IN")
	private int isIn;
	@Column(name="IS_OUT")
	private int isOut;
	private int ordernumber;
	
	@ManyToOne
	@JoinColumn(name="QUERY_ID")
	private QueryEntity queryEntity;
	
	@Override
	public String toString() {
		return name + " " + type + " := " + value;
		
	}
	
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
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
		if (!(object instanceof QueryParameterEntity)) {
			return false;
		}
		QueryParameterEntity other = (QueryParameterEntity) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getValue() {
		return value;
	}
	
	public void setValue(String value) {
		this.value = value;
	}
	
	public String getType() {
		return type;
	}
	
	public void setType(String type) {
		this.type = type;
	}
	
	public int getIsIn() {
		return isIn;
	}
	
	public void setIsIn(Integer isIn) {
		if (isIn == null) {
			this.isIn = 0;
		} else {
			this.isIn = isIn;
		}
	}
	
	public int getIsOut() {
		return isOut;
	}
	
	public void setIsOut(Integer isOut) {
		if (isOut == null) {
			this.isOut = 0;
		} else {
			this.isOut = isOut;
		}
	}
	
	public int getOrdernumber() {
		return ordernumber;
	}
	
	public void setOrdernumber(int ordernumber) {
		this.ordernumber = ordernumber;
	}

	public QueryEntity getQueryEntity() {
		return queryEntity;
	}

	public void setQueryEntity(QueryEntity queryEntity) {
		this.queryEntity = queryEntity;
	}
	
}
