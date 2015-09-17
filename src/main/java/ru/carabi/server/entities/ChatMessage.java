package ru.carabi.server.entities;

import java.io.Serializable;
import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;

/**
 * Сообщение в чате.
 * @author sasha<kopilov.ad@gmail.com>
 */
@Entity
@NamedQueries({
@NamedQuery(name = "getDialog",
	query = "select CM from ChatMessage CM where CM.ownerId = :user and (CM.sent >= :recently or CM.received is null) and (CM.senderId = :interlocutor or CM.receiverId = :interlocutor) order by CM.sent"),
@NamedQuery(name = "searchInDialog",
	query = "select CM from ChatMessage CM where CM.ownerId = :user and (CM.sent >= :recently or CM.received is null) and (CM.senderId = :interlocutor or CM.receiverId = :interlocutor) and upper(CM.messageText) like :search order by CM.sent"),
@NamedQuery(name = "getMessagesGroup",
	query = "select CM from ChatMessage CM where CM.extensionTypeId = :extensionTypeIsGroup and CM.extensionValue = :messagesGroupSysname and CM.sent >= :recently order by CM.sent"),
@NamedQuery(name = "searchInMessagesGroup",
	query = "select CM from ChatMessage CM where CM.extensionTypeId = :extensionTypeIsGroup and CM.extensionValue = :messagesGroupSysname and CM.sent >= :recently and upper(CM.messageText) like :search order by CM.sent"),
@NamedQuery(name = "getSentByReceived",
	query = "select CM from ChatMessage CM where CM.receivedMessageId = :received and CM.receivedMessageServerId = :server"),
@NamedQuery(name = "getUnreadMessagesCount",
	query = "select count(CM.id) from ChatMessage CM where CM.ownerId = :user and CM.receiverId = :user and CM.received is null"),
@NamedQuery(name = "getUnreadMessagesSenders",
	query = "select CM.senderId, count(CM.senderId) from ChatMessage CM where CM.received is null and CM.ownerId = :user and CM.receiverId = :user group by CM.senderId"),
@NamedQuery(name = "getLastUserMessages",
	query = "select CM.id, CM.messageText from ChatMessage CM where CM.ownerId = :user and CM.receiverId = :user and CM.senderId = :sender order by CM.sent desc"),
@NamedQuery(name = "getOldUnreadMessages",
	query = "select CM.id from ChatMessage CM where CM.ownerId = :user and CM.receiverId = :user and CM.senderId = :sender and CM.received is null and CM.sent <= :sentBefore"),
@NamedQuery(name = "getRecentlyMessagesData",
	query = "select CM.senderId, CM.receiverId, CM.sent from ChatMessage CM where CM.ownerId = :user and CM.sent >= :recently and CM.extensionTypeId is null order by CM.sent desc"),
@NamedQuery(name = "deleteMessagesList",
	query = "delete from ChatMessage CM where CM.ownerId = :user and CM.id in :idlist"),
@NamedQuery(name = "getUserMessagesAttachments",
		query = "select F from FileOnServer F where F in (select CM.attachment from ChatMessage CM where CM.ownerId = :user and CM.id in :idlist)" ),
@NamedQuery(name = "getMessagesWithAttachment",
		query = "select CM.id from ChatMessage CM where CM.attachment.id = :attachment_id")
})
@Table(name="CHAT_MESSAGE")
public class ChatMessage implements Serializable {
	
	@Id
	@Column(name="MESSAGE_ID")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(name="OWNER_ID")
	private Long ownerId;
	
	@Column(name="SENDER_ID")
	private Long senderId;
	
	@Temporal(javax.persistence.TemporalType.TIMESTAMP)
	private Date sent;
	
	@Column(name="RECEIVER_ID")
	private Long receiverId;
	
	@Temporal(javax.persistence.TemporalType.TIMESTAMP)
	private Date received;
	
	@Column(name="MESSAGE_TEXT")
	private String messageText;
	
	@Column(name="RECEIVED_MESSAGE_ID")
	private Long receivedMessageId;
	
	@Column(name="RECEIVED_MESSAGE_SERVER_ID")
	private Integer receivedMessageServerId;
	
	@OneToOne
	@JoinColumn(name="ATTACHMENT_ID")
	private FileOnServer attachment;
	
	@Column(name="EXTENSION_TYPE_ID")
	private Integer extensionTypeId;
	
	@Column(name="EXTENSION_VALUE")
	private String extensionValue;
	
	public Long getId() {
		return id;
	}
	
	public void setId(Long id) {
		this.id = id;
	}
	
	public Long getOwnerId() {
		return ownerId;
	}
	
	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}
	
	public Long getSenderId() {
		return senderId;
	}
	
	public void setSenderId(Long senderId) {
		this.senderId = senderId;
	}
	
	public Date getSent() {
		return sent;
	}
	
	public void setSent(Date sent) {
		this.sent = sent;
	}
	
	public Long getReceiverId() {
		return receiverId;
	}
	
	public void setReceiverId(Long recieverId) {
		this.receiverId = recieverId;
	}
	
	public Date getReceived() {
		return received;
	}
	
	public void setReceived(Date received) {
		this.received = received;
	}
	
	public String getMessageText() {
		return messageText;
	}
	
	public void setMessageText(String messageText) {
		this.messageText = messageText;
	}
	
	public Long getReceivedMessageId() {
		return receivedMessageId;
	}
	
	public void setReceivedMessageId(Long sentMessageId) {
		this.receivedMessageId = sentMessageId;
	}
	
	public Integer getReceivedMessageServerId() {
		return receivedMessageServerId;
	}
	
	public void setReceivedMessageServerId(Integer receivedMessageServerId) {
		this.receivedMessageServerId = receivedMessageServerId;
	}
	
	public FileOnServer getAttachment() {
		return attachment;
	}
	
	public void setAttachment(FileOnServer attachment) {
		this.attachment = attachment;
	}
	
	public Integer getExtensionTypeId() {
		return extensionTypeId;
	}
	
	public void setExtensionTypeId(Integer extensionTypeId) {
		this.extensionTypeId = extensionTypeId;
	}
	
	public String getExtensionValue() {
		return extensionValue;
	}
	
	public void setExtensionValue(String extensionValue) {
		this.extensionValue = extensionValue;
	}
}
