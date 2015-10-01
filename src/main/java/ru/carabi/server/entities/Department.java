package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.Collection;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * Юридическое лицо, подразделение.
 * Объединяет пользователей по месту работы и используемому ПО.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="DEPARTMENT")
@NamedQueries({
	@NamedQuery(name="getDepartmantInfo",
		query = "select D from Department D where D.sysname = :sysname")
})
public class Department extends AbstractEntity implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="DEPARTMENT_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	private String name;
	private String sysname;
	private String description;
	
	@Column(name="PARENT_DEPARTMENT_ID")
	private Integer parentDepartmentId;
	
	@ManyToOne
	@JoinColumn(name="DEFAULT_SCHEMA_ID")
	//Основная неядровая БД
	private ConnectionSchema defaultSchema;
	
	@ManyToOne
	@JoinColumn(name="MAIN_SERVER_ID")
	//Прикладной сервер, к которому участники будут обращаться чаще всего
	private CarabiAppServer mainServer;
	
	@OneToMany(mappedBy="department")
	private Collection<CarabiUser> members;
	
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
	
	public Integer getParentDepartmentId() {
		return parentDepartmentId;
	}
	
	public void setParentDepartmentId(Integer parentDepartmentId) {
		this.parentDepartmentId = parentDepartmentId;
	}
	
	public ConnectionSchema getDefaultSchema() {
		return defaultSchema;
	}
	
	public void setDefaultSchema(ConnectionSchema defaultSchema) {
		this.defaultSchema = defaultSchema;
	}
	
	public CarabiAppServer getMainServer() {
		return mainServer;
	}
	
	public void setMainServer(CarabiAppServer mainServer) {
		this.mainServer = mainServer;
	}
	
	public Collection<CarabiUser> getMembers() {
		return members;
	}
	
	public void setMembers(Collection<CarabiUser> members) {
		this.members = members;
	}
	
}
