package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.List;
import javax.persistence.CascadeType;
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
import javax.persistence.OrderBy;
import javax.persistence.Table;

/**
 * Объект для сохранения Oracle-запросов в служебной БД.
 * 
 * @author sasha
 */
@Entity
@Table(name="ORACLE_QUERY")
@NamedQueries({
	@NamedQuery(name="findNamedQuery",
		query="SELECT Q.id FROM QueryEntity Q where Q.sysname = :queryName"),
	@NamedQuery(name = "selectCategoryQueries",
		//query="select Q from QueryEntity Q where Q.category.id = :categoryId and Q.isDeprecated = false order by Q.name"),
		query="select Q from QueryEntity Q where Q.category.id = :categoryId order by Q.sysname, Q.name"),
	@NamedQuery(name = "selectAllQueries",
		query="select Q from QueryEntity Q order by Q.name"),
})
public class QueryEntity extends AbstractEntity implements Serializable {
	private static final long serialVersionUID = 2L;
	
	@Id
	@Column(name="QUERY_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private String name;
	@ManyToOne
	@JoinColumn(name="CATEGORY_ID")
	private QueryCategory category;
	
	@Column(name="SQL_QUERY")
	private String body;
	
	@Column(name="IS_EXECUTABLE")
	private boolean isExecutable;
	
	@Column(name="IS_DEPRECATED")
	private boolean isDeprecated;
	
	@OneToMany(cascade=CascadeType.ALL, mappedBy="queryEntity" )
	@OrderBy("ordernumber")
	private List<QueryParameterEntity> parameters;
	
	@ManyToOne
	@JoinColumn(name="SCHEMA_ID")
	private ConnectionSchema schema;
	
	@Column(name="SYSNAME")
	private String sysname;
	
	@Override
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
		this.id = id;
	}
	
	public String getSysname() {
		return sysname;
	}
	
	public void setSysname(String sysname) {
		this.sysname = sysname;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public QueryCategory getCategory() {
		return category;
	}
	
	public void setCategory(QueryCategory category) {
		this.category = category;
	}
	
	public String getBody() {
		return body;
	}
	
	public void setBody(String body) {
		this.body = body;
	}
	
	public boolean getIsExecutable() {
		return isExecutable;
	}
	
	public void setIsExecutable(boolean isExecutable) {
		this.isExecutable = isExecutable;
	}
	
	public boolean isSql() {
		return !getIsExecutable();
	}
	
	public boolean getIsDeprecated() {
		return isDeprecated;
	}
	
	public void setIsDeprecated(boolean isDeprecated) {
		this.isDeprecated = isDeprecated;
	}
	
	public List<QueryParameterEntity> getParameters() {
		return parameters;
	}
	
	public void setParameters(List<QueryParameterEntity> parameters) {
		this.parameters = parameters;
	}
	
	public ConnectionSchema getSchema() {
		return schema;
	}
	
	public void setSchema(ConnectionSchema schema) {
		this.schema = schema;
	}
}
