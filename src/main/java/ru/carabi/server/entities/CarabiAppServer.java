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
 * Сведения о сервере с программным обеспечением Караби.
 * @author sasha <kopilov.ad@gmail.com>
 */
@Entity
@Table(name="APPSERVER")
@NamedQueries ({
	@NamedQuery(name="findCarabiServer",
		query="SELECT S FROM CarabiAppServer S where S.sysname = :serverName"),
	@NamedQuery(name="findMasterServer",
		query="SELECT S FROM CarabiAppServer S where S.isMasterInt > 0"),
	@NamedQuery(name="getAllServers",
		query="select S from CarabiAppServer S"),
	@NamedQuery(name="getAllUserSevers",
		query="select S from CarabiAppServer S where S in (select U.appServer from UserLogon U where U.user = :user and U.lastActive > :newer_than)")
})
public class CarabiAppServer implements Serializable {
	private static final long serialVersionUID = 1L;
	@Id
	@Column(name="APPSERVER_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	private String name;
	private String sysname;
	private String computer;
	private String contextroot;
	@Column(name="GLASSFISH_PORT")
	private int glassfishPort;
	@Column(name="EVENTER_PORT")
	private int eventerPort;
	private String description;
	@Column(name="IS_MASTER")
	private int isMasterInt;
	@Column(name="IS_ENABLED")
	private int isEnabledInt;
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
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
		if (!(object instanceof CarabiAppServer)) {
			return false;
		}
		CarabiAppServer other = (CarabiAppServer) object;
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
	
	public String getSysname() {
		return sysname;
	}
	
	public void setSysname(String sysname) {
		this.sysname = sysname;
	}
	
	public String getComputer() {
		return computer;
	}
	
	public void setComputer(String computer) {
		this.computer = computer;
	}
	
	public String getContextroot() {
		return contextroot;
	}
	
	public void setContextroot(String contextroot) {
		this.contextroot = contextroot;
	}
	
	public int getGlassfishPort() {
		return glassfishPort;
	}
	
	public void setGlassfishPort(int glassfishPort) {
		this.glassfishPort = glassfishPort;
	}
	
	public int getEventerPort() {
		return eventerPort;
	}
	
	public void setEventerPort(int eventerPort) {
		this.eventerPort = eventerPort;
	}
	
	public String getDescription() {
		return description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}
	
	public int getIsMasterInt() {
		return isMasterInt;
	}
	
	public void setIsMasterInt(int isMasterInt) {
		this.isMasterInt = isMasterInt;
	}
	
	public boolean isMaster() {
		return isMasterInt > 0;
	}
	
	public void setIsMaster(boolean isMaster) {
		this.isMasterInt = isMaster ? 1 : 0;
	}
	
	public int getIsEnabledInt() {
		return isEnabledInt;
	}

	public void setIsEnabledInt(int isEnabledInt) {
		this.isEnabledInt = isEnabledInt;
	}

	public boolean isEnabled() {
		return isEnabledInt > 0;
	}

	public void setIsEnabled(boolean isEnabled) {
		this.isEnabledInt = isEnabled ? 1 : 0;
	}

	@Override
	public String toString() {
		return name + " (" + sysname + "): http://" + computer + ":" + glassfishPort + "/" + contextroot + "\n\n" + description;
	}
	
}
