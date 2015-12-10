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
 * Право пользователя.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="USER_PERMISSION")
public class Permission extends AbstractEntity implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="PERMISSION_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	private String name;
	private String sysname;
	private String description;
	
	@Column(name="PARENT_PERMISSION")
	private Integer parentPermissionId;
	
	@Column(name="PERMISSION_TO_ASSIGN")
	private Integer permissionToAssignId;
	
	@Override
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
	
	public Integer getParentPermissionId() {
		return parentPermissionId;
	}
	
	public void setParentPermissionId(Integer parentPermissionId) {
		this.parentPermissionId = parentPermissionId;
	}
	
	public Integer getPermissionToAssignId() {
		return permissionToAssignId;
	}
	
	public void setPermissionToAssignId(Integer permissionToAssignId) {
		this.permissionToAssignId = permissionToAssignId;
	}
}
