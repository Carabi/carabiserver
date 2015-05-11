package ru.carabi.server.entities;

import java.io.Serializable;
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

/**
 * Группа сообщений чата.
 * @author sasha<kopilov.ad@gmail.com>
 */

@NamedQueries ({
	@NamedQuery(name="findMessageGroupBySysname",
		query="select MG from MessagesGroup MG where MG.sysname = :sysname")
})
@Entity
@Table(name="MESSAGES_GROUP")
public class MessagesGroup implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="MESSAGES_GROUP_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;
	
	private String name;
	private String sysname;
	private String description;
	
	@ManyToOne
	@JoinColumn(name="SERVER_ID")
	//Сервер, на котором располагаются сообщения
	private CarabiAppServer server;
	
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
	
	public CarabiAppServer getServer() {
		return server;
	}
	
	public void setServer(CarabiAppServer server) {
		this.server = server;
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
		if (!(object instanceof MessagesGroup)) {
			return false;
		}
		MessagesGroup other = (MessagesGroup) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}
	
	@Override
	public String toString() {
		return "ru.carabi.server.entities.MessageGroup[ id=" + id + " ]";
	}
}
