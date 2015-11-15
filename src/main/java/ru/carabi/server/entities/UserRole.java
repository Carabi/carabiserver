package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.Collection;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 * Роль пользователя, задающая набор прав.
 ** @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@NamedQueries({
	@NamedQuery(name = "getAllUsersRoles",
		query = "select UR from UserRole UR")
})
@Table(name="USER_ROLE")
public class UserRole extends AbstractEntity implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="ROLE_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	private String name;
	private String sysname;
	private String description;
	
	@ManyToMany
	@JoinTable(
		name="ROLE_HAS_PERMISSION",
		joinColumns=
			@JoinColumn(name="ROLE_ID", referencedColumnName="ROLE_ID"),
		inverseJoinColumns=
			@JoinColumn(name="PERMISSION_ID", referencedColumnName="PERMISSION_ID")
	)
	private Collection<Permission> permissions;
	
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
	
	public Collection<Permission> getPermissions() {
		return permissions;
	}
	
	public void setPermissions(Collection<Permission> permissions) {
		this.permissions = permissions;
	}
}
