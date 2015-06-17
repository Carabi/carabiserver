package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

/**
 *
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="THUMBNAIL")
@NamedQueries ({
	@NamedQuery(name = "findThumbnail",
		query = "select T from ThumbnailMeta T where T.original = :original and T.width = :width and T.height = :height"),
	@NamedQuery(name = "findAllThumbnails",
		query = "select T from ThumbnailMeta T where T.original = :original")
})
public class ThumbnailMeta implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
	@ManyToOne
	@JoinColumn(name="ORIGINAL_ID")
	FileOnServer original;
	
	@Id
	Integer height;
	
	@Id
	Integer width;
	
	@ManyToOne
	@JoinColumn(name="THUMBNAIL_ID")
	FileOnServer thumbnail;
	
	public FileOnServer getOriginal() {
		return original;
	}
	
	public void setOriginal(FileOnServer original) {
		this.original = original;
	}
	
	public Integer getHeight() {
		return height;
	}
	
	public void setHeight(Integer height) {
		this.height = height;
	}
	
	public Integer getWidth() {
		return width;
	}
	
	public void setWidth(Integer width) {
		this.width = width;
	}
	
	public FileOnServer getThumbnail() {
		return thumbnail;
	}
	
	public void setThumbnail(FileOnServer thumbnail) {
		this.thumbnail = thumbnail;
	}
	
	@Override
	public int hashCode() {
		int hash = 0;
		hash += (original.hashCode() + width*1020*1024 + height);
		return hash;
	}

	@Override
	public boolean equals(Object object) {
		if (!(object instanceof ThumbnailMeta)) {
			return false;
		}
		ThumbnailMeta other = (ThumbnailMeta) object;
		if ((this.original == null && other.original != null) || (this.original != null && !this.original.equals(other.original)) || 
				(this.height == null && other.height != null) || (this.height != null && !this.height.equals(other.height)) ||
				(this.width == null && other.width != null) || (this.width != null && !this.width.equals(other.width))
				) {
			return false;
		}
		return true;
	}

}
