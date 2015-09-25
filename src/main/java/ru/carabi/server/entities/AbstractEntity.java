package ru.carabi.server.entities;

import java.io.Serializable;

/**
 *
 * @author sasha
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
		if (!(object instanceof Department)) {
			return false;
		}
		Department other = (Department) object;
		if ((this.getId() == null && other.getId() != null) || (this.getId() != null && !this.getId().equals(other.getId()))) {
			return false;
		}
		return true;
	}
	
	@Override
	public String toString() {
		return getClass().getCanonicalName() + "[ id=" + getId() + " ]";
	}
	
	public static final AbstractEntity createSample(Class c) throws InstantiationException, IllegalAccessException {
		return (AbstractEntity) c.newInstance();
	}
}
