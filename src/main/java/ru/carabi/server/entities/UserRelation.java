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
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 *
 * @author sasha
 */
@Entity
@Table(name="USER_RELATION")
@NamedQueries ({
@NamedQuery(name = "findUsersRelation",
		query = "select UR from UserRelation UR where UR.mainUser = :mainUser and UR.relatedUser = :relatedUser"),
@NamedQuery(name = "findUsersRelations",
		query = "select UR from UserRelation UR where UR.mainUser = :mainUser and UR.relatedUser in :relatedUsers"),
@NamedQuery(name = "deleteUsersRelation",
		query = "delete from UserRelation UR where UR.mainUser = :mainUser and UR.relatedUser = :relatedUser")
})
public class UserRelation implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@Column(name="USER_RELATION_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name="MAIN_USER_ID")
	CarabiUser mainUser;
	
	
	@ManyToOne
	@JoinColumn(name="RELATED_USER_ID")
	CarabiUser relatedUser;
	
	@ManyToMany
	@JoinTable(
		name="RELATION_HAS_TYPE",
		joinColumns=
			@JoinColumn(name="USER_RELATION_ID", referencedColumnName="USER_RELATION_ID"),
		inverseJoinColumns=
			@JoinColumn(name="RELATION_TYPE_ID", referencedColumnName="RELATION_TYPE_ID")
	)
	private Collection<UserRelationType> relationTypes;
	
	public CarabiUser getMainUser() {
		return mainUser;
	}
	
	public void setMainUser(CarabiUser user) {
		this.mainUser = user;
	}
	
	public CarabiUser getRelatedUser() {
		return relatedUser;
	}
	
	public void setRelatedUser(CarabiUser contact) {
		this.relatedUser = contact;
	}

	public Collection<UserRelationType> getRelationTypes() {
		return relationTypes;
	}

	public void setRelationTypes(Collection<UserRelationType> relationTypes) {
		this.relationTypes = relationTypes;
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
		UserRelation other = (UserRelation) object;
		if ((this.id == null && other.id != null) || (this.id != null && !this.id.equals(other.id))) {
			return false;
		}
		return true;
	}

}
