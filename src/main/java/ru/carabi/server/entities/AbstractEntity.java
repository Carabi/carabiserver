package ru.carabi.server.entities;

import java.io.Serializable;

/**
 * Абстрактный класс с дублирующимися функциями JPA-сущностей.
 * @author sasha<kopilov.ad@gmail.com>
 */
public abstract class AbstractEntity implements Serializable {
	
	public abstract Number getId();
	
	@Override
	public int hashCode() {
		int hash = 0;
		hash += (getId() != null ? getId().hashCode() : 0);
		return hash;
	}
	
	@Override
	public boolean equals(Object object) {
		// TODO: Warning - this method won't work in the case the id fields are not set
		if (!(this.getClass().isInstance(object))) {
			return false;
		}
		AbstractEntity other = (AbstractEntity) object;
		if ((this.getId() == null && other.getId() != null) || (this.getId() != null && !this.getId().equals(other.getId()))) {
			return false;
		}
		return true;
	}
	
	@Override
	public String toString() {
		return getClass().getCanonicalName() + "[ id=" + getId() + " ]";
	}
	
}
