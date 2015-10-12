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
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToOne;
import javax.persistence.Temporal;

/**
 * Публикация для отображения в Face.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@NamedQueries({ 
	@NamedQuery (name="getUserPublications",
		query = "select P from Publication P where P.destinatedForUser = :user or P.destinatedForDepartment = :department or P.destinatedForDepartment = :corporation or (P.destinatedForUser is null and P.destinatedForDepartment is null)"
	)
})
public class Publication extends AbstractEntity implements Serializable  {
	private static final long serialVersionUID = 1L;
	@Id
	@Column(name = "PUBLICATION_ID")
	@GeneratedValue(strategy = GenerationType.AUTO)
	private Long id;
	
	private String name;
	private String description;
	
	@OneToOne
	@JoinColumn(name="ATTACHMENT_ID")
	private FileOnServer attachment;
	
	@ManyToOne
	@JoinColumn(name="DESTINATED_FOR_DEPARTMENT")
	private Department destinatedForDepartment;
	
	@ManyToOne
	@JoinColumn(name="DESTINATED_FOR_USER")
	private CarabiUser destinatedForUser;
	
	@ManyToOne
	@JoinColumn(name="PERMISSION_TO_READ")
	private Permission permissionToRead;
	
	@Column(name="ISSUE_DATE")
	@Temporal(javax.persistence.TemporalType.DATE)
	private Date issueDate;
	
	@Override
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
	
	public String getDescription() {
		return description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}
	
	public FileOnServer getAttachment() {
		return attachment;
	}
	
	public void setAttachment(FileOnServer attachment) {
		this.attachment = attachment;
	}
	
	public Department getDestinatedForDepartment() {
		return destinatedForDepartment;
	}
	
	public void setDestinatedForDepartment(Department destinatedForDepartment) {
		this.destinatedForDepartment = destinatedForDepartment;
	}
	
	public CarabiUser getDestinatedForUser() {
		return destinatedForUser;
	}
	
	public void setDestinatedForUser(CarabiUser destinatedForUser) {
		this.destinatedForUser = destinatedForUser;
	}
	
	public Permission getPermissionToRead() {
		return permissionToRead;
	}
	
	public void setPermissionToRead(Permission permissionToRead) {
		this.permissionToRead = permissionToRead;
	}
	
	public Date getIssueDate() {
		return issueDate;
	}
	
	public void setIssueDate(Date issueDate) {
		this.issueDate = issueDate;
	}
}
