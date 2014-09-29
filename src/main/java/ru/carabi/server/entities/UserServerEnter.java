package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 * Вход пользователя на сервер.
 * Объект для фиксирования статистики (сколько раз конкретный пользователь посещал
 * конкретный сервер).
 * @author sasha
 */
@Entity
@Table(name="USER_AT_SERVER_ENTER")
@NamedQuery(name="getUserServerEnter", 
	query = "select USE from UserServerEnter USE where USE.user = :user and USE.server = :server"
)
public class UserServerEnter implements Serializable {
	@Id //Long id
	@ManyToOne
	@JoinColumn(name="USER_ID")
	private CarabiUser user;
	
	@Id
	@ManyToOne
	@JoinColumn(name="SERVER_ID")
	private CarabiAppServer server;
	
	@Column(name="NUMBER_OF_ENTERS")
	private long numberOfEnters;
	
//	public Long getId() {
//		return id;
//	}
//	
//	public void setId(Long id) {
//		this.id = id;
//	}
	
	public CarabiUser getUser() {
		return user;
	}
	
	public void setUser(CarabiUser user) {
		this.user = user;
	}
	
	public CarabiAppServer getServer() {
		return server;
	}
	
	public void setServer(CarabiAppServer server) {
		this.server = server;
	}
	
	public long getNumberOfEnters() {
		return numberOfEnters;
	}
	
	public void setNumberOfEnters(long numberOfEnters) {
		this.numberOfEnters = numberOfEnters;
	}

	public void increment() {
		numberOfEnters++;
	}
}
