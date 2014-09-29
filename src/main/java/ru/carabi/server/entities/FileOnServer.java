package ru.carabi.server.entities;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Данные о файле на сервере, содержащем ядровую базу.
 * Скопировано с {@link ChatAttachment}, отличие в том, что файлы ChatAttachment
 * хранятся на current-сервере, и данные о них --  в current-базе.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@Table(name="FILE")
public class FileOnServer implements Serializable {
	
	@Id
	@Column(name="FILE_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(name="NAME")
	private String name;
	
	@Column(name="MIME_TYPE")
	private String mimeType;
	
	@Column(name="CONTENT_ADDRESS")
	private String contentAddress;
	
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
		this.id = id;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getMimeType() {
		return mimeType;
	}
	
	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public String getContentAddress() {
		return contentAddress;
	}
	
	public void setContentAddress(String contentAddress) {
		this.contentAddress = contentAddress;
	}
	
	@Column(name="CONTENT_LENGTH")
	private Long contentLength;
	
	public Long getContentLength() {
		return contentLength;
	}
	
	public void setContentLength(Long contentLength) {
		this.contentLength = contentLength;
	}
	
}
