package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;

/**
 * Пользователь системы Carabi.
 * Может работать с любой разрешённой схемой Oracle, данные о нём самом хранятся в Derby.
 * 
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="CARABI_USER")
@NamedQueries({
	@NamedQuery(name="findUser",
		query = "select U.id from CarabiUser U where U.login = :login"),
	@NamedQuery(name="getUserInfo",
		query = "select U from CarabiUser U where U.login = :login"),
	@NamedQuery(name="getAllUsersList",
		query = "select U from CarabiUser U order by U.firstname, U.middlename, U.lastname"),
	@NamedQuery(name="getSelectedUsersList",
		query = "select U from CarabiUser U where U.id in :idlist"),// order by U.firstname, U.middlename, U.lastname
	@NamedQuery(name="getUsersListSearch",
		query = "select U from CarabiUser U " +
				"where upper(U.login) like :search or upper(U.firstname) like :search " + 
				"or upper(U.middlename) like :search or upper(U.lastname) like :search " + 
				"or upper(U.role) like :search or upper(U.department) like :search " + 
				"order by U.firstname, U.middlename, U.lastname "),
	@NamedQuery(name="getSelectedUsersListSearch",
		query = "select U from CarabiUser U where U.id in :idlist and (" +
				"upper(U.login) like :search or upper(U.firstname) like :search " + 
				"or upper(U.middlename) like :search or upper(U.lastname) like :search " + 
				"or upper(U.role) like :search or upper(U.department) like :search )" + 
				"order by U.firstname, U.middlename, U.lastname "),
	@NamedQuery(name="getRelatedUsersList",
		query = "select UR.relatedUser from UserRelation UR where UR.mainUser = :user")
})
public class CarabiUser implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="USER_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String login;
	private String password = "==";
	private String firstname;
	private String middlename;
	private String lastname;
	private String role;
	private String department;
	
	@ManyToOne
	@JoinColumn(name="DEFAULT_SCHEMA_ID")
	private ConnectionSchema defaultSchema;//схема Carabi, с которой работает пользователь
	
	@ManyToMany
	@JoinTable(
		name="ALLOWED_SCHEMAS",
		joinColumns=
			@JoinColumn(name="USER_ID", referencedColumnName="USER_ID"),
		inverseJoinColumns=
			@JoinColumn(name="SCHEMA_ID", referencedColumnName="SCHEMA_ID")
	)
	private Collection<ConnectionSchema> allowedSchemas;
	
	@ManyToOne
	@JoinColumn(name="MAIN_SERVER_ID")
	//Прикладной сервер, к которому (предположительно) пользователь обращается
	//чаще всего и где могут храниться его данные в выделенной базе
	private CarabiAppServer mainServer;
	
	@OneToMany(mappedBy="user")
	private Collection<UserServerEnter> entersToServers;
	
	@OneToOne
	@JoinColumn(name="AVATAR")
	private FileOnServer avatar;
	
	@Temporal(javax.persistence.TemporalType.TIMESTAMP)
	private Date lastActive; //Время, когда пользователь последний раз заходил
	
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
		this.id = id;
	}
	
	public String getLogin() {
		return login;
	}
	
	public void setLogin(String login) {
		this.login = login;
	}
	
	public String getPassword() {
		return password;
	}
	
	public void setPassword(String password) {
		this.password = password;
	}
	
	public String getFirstname() {
		return firstname;
	}
	
	public void setFirstname(String firstname) {
		this.firstname = firstname;
	}
	
	public String getMiddlename() {
		return middlename;
	}
	
	public void setMiddlename(String middlename) {
		this.middlename = middlename;
	}
	
	public String getLastname() {
		return lastname;
	}
	
	public void setLastname(String lastname) {
		this.lastname = lastname;
	}
	
	public String getRole() {
		return role;
	}
	
	public void setRole(String role) {
		this.role = role;
	}
	
	public String getDepartment() {
		return department;
	}
	
	public void setDepartment(String department) {
		this.department = department;
	}
	
	public ConnectionSchema getDefaultSchema() {
		return defaultSchema;
	}
	
	public void setDefaultSchema(ConnectionSchema defaultSchema) {
		this.defaultSchema = defaultSchema;
	}
	
	public Collection<ConnectionSchema> getAllowedSchemas() {
		return allowedSchemas;
	}
	
	public void setAllowedSchemas(Collection<ConnectionSchema> allowedSchemas) {
		this.allowedSchemas = allowedSchemas;
	}
	
	public void addAllowedSchema(ConnectionSchema allowedSchema) {
		if (allowedSchemas == null) {
			allowedSchemas = new HashSet<ConnectionSchema>();
		}
		allowedSchemas.add(allowedSchema);
	}
	
	public CarabiAppServer getMainServer() {
		return mainServer;
	}
	
	public void setMainServer(CarabiAppServer mainServer) {
		this.mainServer = mainServer;
	}
	
	public Collection<UserServerEnter> getEntersToServers() {
		return entersToServers;
	}
	
	public void setEntersToServers(Collection<UserServerEnter> entersToServers) {
		this.entersToServers = entersToServers;
	}
	
	public FileOnServer getAvatar() {
		return avatar;
	}

	public void setAvatar(FileOnServer avatar) {
		this.avatar = avatar;
	}

	public Date getLastActive() {
		return lastActive;
	}

	public void setLastActive(Date lastActive) {
		this.lastActive = lastActive;
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
		CarabiUser other = (CarabiUser) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}

	public void setAll(Map<String, Object> newData) {
		if (newData.containsKey("FIRSTNAME")) {
			setFirstname((String)newData.get("FIRSTNAME"));
		}
		if (newData.containsKey("MIDDLENAME")) {
			setMiddlename((String)newData.get("MIDDLENAME"));
		}
		if (newData.containsKey("LASTNAME")) {
			setLastname((String)newData.get("LASTNAME"));
		}
		if (newData.containsKey("PASSWORD")) {
			setPassword((String)newData.get("PASSWORD"));
		}
	}

}
