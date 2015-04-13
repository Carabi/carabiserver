package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;

/**
 * Сведения о версии продукта Караби. Для хранения в базе через JPA и выдачу через SOAP
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="PRODUCT_VERSION")
public class ProductVersion implements Serializable {
	private static final long serialVersionUID = 1L;
	@Id
	@Column(name="PRODUCT_VERSION_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@ManyToOne
	@JoinColumn(name="PRODUCT_ID")
	private SoftwareProduct carabiProduct;
	
	@Column(name="VERSION_NUMBER")
	private String versionNumber;
	
	@Column(name="ISSUE_DATE")
	@Temporal(javax.persistence.TemporalType.DATE)
	private Date issueDate;
	
	String singularity;
	
	@Column(name="DOWNLOAD_URL")
	String downloadUrl;
	
	@Column(name="IS_SIGNIFICANT_UPDATE")
	int isSignificantUpdate;
	
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
		if (!(object instanceof ProductVersion)) {
			return false;
		}
		ProductVersion other = (ProductVersion) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}
	
	@Override
	public String toString() {
		return "ru.carabi.server.ProductVersion[ id=" + id + " ]";
	}
	
	public SoftwareProduct getCarabiProduct() {
		return carabiProduct;
	}
	
	public void setCarabiProduct(SoftwareProduct carabiProduct) {
		this.carabiProduct = carabiProduct;
	}
	
	public String getVersionNumber() {
		return versionNumber;
	}
	
	public void setVersionNumber(String versionNumber) {
		this.versionNumber = versionNumber;
	}
	
	public Date getIssueDate() {
		return issueDate;
	}
	
	public void setIssueDate(Date issueDate) {
		this.issueDate = issueDate;
	}
	
	public String getSingularity() {
		return singularity;
	}
	
	public void setSingularity(String singularity) {
		this.singularity = singularity;
	}
	
	public String getDownloadUrl() {
		return downloadUrl;
	}
	
	public void setDownloadUrl(String downloadUrl) {
		this.downloadUrl = downloadUrl;
	}
	
	public int getIsSignificantUpdate() {
		return isSignificantUpdate;
	}
	
	public boolean isSignificantUpdate() {
		return isSignificantUpdate != 0;
	}
	
	public void setIsSignificantUpdate(int isSignificantUpdate) {
		this.isSignificantUpdate = isSignificantUpdate;
	}
}
