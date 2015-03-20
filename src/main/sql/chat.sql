create schema CARABI_CHAT;
set SEARCH_PATH to CARABI_CHAT;

CREATE VIEW dual as select 0 as dummy;

/**
 * Пользовательский файл на текущем сервере
 */
create sequence file_id_gen;
create table FILE (
	FILE_ID bigint primary key default nextval('file_id_gen'),
	NAME varchar(1024) not null,
	MIME_TYPE varchar(64),
	CONTENT_ADDRESS varchar(1024) unique,
	CONTENT_LENGTH bigint
);
create index FILE_NAME on FILE(NAME);

/**
 * Сообщения в чате
 */
create sequence message_id_gen;
create table CHAT_MESSAGE (
	MESSAGE_ID bigint primary key default nextval('message_id_gen'),
	OWNER_ID bigint not null, --id пользователя (хранящегося в отдельной базе) -- обладателя "ящика"
	SENDER_ID bigint not null, --id отправителя
	SENT timestamp not null, -- дата и время отправки
	RECEIVER_ID bigint not null, --id получателя
	RECEIVED timestamp, --дата и время прочтения (если null -- не прочитано)
	RECEIVED_MESSAGE_ID bigint, --в "отправленных" сообщениях -- ссылка на парное ему "входящее".
	RECEIVED_MESSAGE_SERVER_ID integer, --сервер со входящим сообщением
	-- Может лежать в другой базе, если пользователей обслуживают разные сервера.
	MESSAGE_TEXT varchar(32000) not null, --текст сообщения или комментарий к пересылаемому файлу
	ATTACHMENT_ID bigint references FILE (FILE_ID), --пересылаемый файл
	EXTENSION_TYPE_ID integer, --тип расширения (если null -- обычное сообщение)
	EXTENSION_VALUE varchar(32000) --информация в расширении (формат определяется клиентом)
);

create index CHAT_MESSAGE_OWNER on CHAT_MESSAGE(OWNER_ID);
create index CHAT_MESSAGE_SENDER on CHAT_MESSAGE(SENDER_ID);
create index CHAT_MESSAGE_RECEIVER on CHAT_MESSAGE(RECEIVER_ID);
create index CHAT_MESSAGE_SENT on CHAT_MESSAGE(SENT);
create index CHAT_MESSAGE_EXTENSION_TYPE on CHAT_MESSAGE(EXTENSION_TYPE_ID);
create index CHAT_MESSAGE_RECEIVED on CHAT_MESSAGE(RECEIVED);


--commit;
