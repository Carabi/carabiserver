set SEARCH_PATH to CARABI_KERNEL;

insert into CONNECTION_SCHEMA (JNDI, NAME, SYSNAME) values
	('jdbc/carabi', 'carabi all', 'carabi'),
	('jdbc/veneta', 'Венета', 'veneta'),
;


INSERT INTO APPSERVER (APPSERVER_ID, NAME, SYSNAME, COMPUTER, CONTEXTROOT) VALUES
	(1,'develop','office','127.0.0.1','carabiserver');


insert into USER_STATUS(STATUS_ID, SYSNAME, NAME)
values(1, 'active', 'Активный'), (2, 'banned', 'Забаненный');

insert into USER_PERMISSION(NAME, SYSNAME) values('Редактирование расширений чата', 'EDIT_CHAT_MESSAGE_TYPES');

insert into CARABI_USER (LOGIN, FIRSTNAME, MIDDLENAME, LASTNAME, DEFAULT_SCHEMA_ID) values
	('kop', 'Александр', 'Дмитриевич', 'Копилов', 1),
;

insert into ALLOWED_SCHEMAS (SCHEMA_ID, USER_ID) values
	(1, 1), (1, 2)
;

insert into PHONE_TYPE (PHONE_TYPE_ID, NAME, SYSNAME) values
	(1, 'IP-телефон', 'SIP'), (2, 'Мобильный', 'mobile'), (3, 'Городской', 'simple')
;

insert into USER_LOGON (TOKEN, LASTACTIVE, PERMANENT)
values ('durfvber74fvqi3447qiviq4vfi73vfdzjycyew673i7q3', '1970-01-01 00:00:00.0', 1);

INSERT INTO message_extension_type ("name", sysname, description, content_type) 
	VALUES ('Задача', 'TASK', NULL, NULL);
INSERT INTO carabi_kernel.message_extension_type ("name", sysname, description, content_type) 
	VALUES ('Групповой чат (многопользовательская беседа или "стена")', 'MESSAGES_GROUP', NULL, 'ID или кодовое имя группового чата, к которому относится сообщение');
INSERT INTO carabi_kernel.message_extension_type ("name", sysname, description, content_type) 
	VALUES ('Входящий звонок', 'CALL_IN', NULL, NULL);
INSERT INTO carabi_kernel.message_extension_type ("name", sysname, description, content_type) 
	VALUES ('Исходящий звонок', 'CALL_OUT', NULL, NULL);
INSERT INTO carabi_kernel.message_extension_type ("name", sysname, description, content_type) 
	VALUES ('Пропущенный звонок', 'CALL_SKIP', NULL, NULL);
