package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 * Разрешённые пользователям схемы.
 * Объект отношения n-n связки {@link CarabiUser} &mdash; {@link ConnectionSchema},
 * используется для выборки с фильтрацией.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="ALLOWED_SCHEMAS")
@NamedQueries ({
	@NamedQuery(name="getSchemaUsersList", 
		query = 
			"SELECT alw.carabiUser.id, alw.carabiUser.login, alw.carabiUser.firstname, alw.carabiUser.middlename, alw.carabiUser.lastname " +
					"FROM AllowedSchema alw WHERE alw.schemaId = :schema_id " +
			"ORDER BY alw.carabiUser.firstname, alw.carabiUser.middlename, alw.carabiUser.lastname "
	),
	@NamedQuery(name="getSchemaUsersWithStatusList", 
		query = 
			"SELECT alw.carabiUser.id, alw.carabiUser.login, alw.carabiUser.firstname, alw.carabiUser.middlename, alw.carabiUser.lastname " +
					"FROM AllowedSchema alw WHERE alw.schemaId = :schema_id " +
					"AND alw.carabiUser.status.sysname = :status " +
			"ORDER BY alw.carabiUser.firstname, alw.carabiUser.middlename, alw.carabiUser.lastname "
	)
})
public class AllowedSchema implements Serializable{
	@Id
	@Column(name = "SCHEMA_ID")
	private int schemaId;
	@Id
	@ManyToOne
	@JoinColumn(name="USER_ID")
	private CarabiUser carabiUser;

	public CarabiUser getCarabiUser() {
		return carabiUser;
	}

	public void setCarabiUser(CarabiUser carabiUser) {
		this.carabiUser = carabiUser;
	}

	public int getSchemaId() {
		return schemaId;
	}

	public void setSchemaId(int schemaId) {
		this.schemaId = schemaId;
	}
}
