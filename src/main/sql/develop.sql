create schema CARABI_KERNEL;
set SEARCH_PATH to CARABI_KERNEL;

CREATE VIEW dual as select 0 as dummy;

/**
 * Базы данных Oracle, с которыми работает система.
 */
create sequence connection_schema_id_gen;
create table CONNECTION_SCHEMA (
	SCHEMA_ID integer primary key default nextval('connection_schema_id_gen'),
	JNDI varchar(256),-- unique not null, --JNDI-имя пула
	ADDRESS varchar(256),
	LOGIN varchar(256),
	PASSWORD varchar(256),
	NAME varchar(256) not null, --Название схемы БД
	SYSNAME varchar(256) unique not null, --Системное имя схемы -- параметр при подключении клиентов
	DESCRIPTION varchar(1024)
);

/*insert into CONNECTION_SCHEMA (JNDI, NAME, SYSNAME) values
	('jdbc/carabi', 'carabi all', 'carabi'),
	('jdbc/veneta', 'Венета', 'veneta'),
;*/

/**
 * Прикладной сервер (Glassfish + Eventer)
 */
create sequence appserver_id_gen;
create table APPSERVER (
	APPSERVER_ID integer primary key default nextval('appserver_id_gen'),
	NAME varchar(256), --Краткое наименование сервера для сис. администратора
	SYSNAME varchar(256) unique not null, --Системное имя сервера, по которому они могут опознать друг друга и себя
	COMPUTER varchar(256) not null, -- адрес компьюрера (IP-адрес или домен)
	CONTEXTROOT varchar(256), --имя развёрнутого в Glassfish приложения Carabi Server
	GLASSFISH_PORT integer default 8080,
	EVENTER_PORT integer default 9234,
	DESCRIPTION varchar(32000), --Описание, если требуется
	IS_MASTER integer default 0,
	IS_ENABLED integer default 1
);
create index APPSERVER_IS_MASTER on APPSERVER(IS_MASTER);
/*
INSERT INTO APPSERVER (APPSERVER_ID, NAME, SYSNAME, COMPUTER, CONTEXTROOT) VALUES
	(1,'develop','office','127.0.0.1','carabiserver');
*/

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
 * Миниатюры картинок
 */
create table THUMBNAIL (
	ORIGINAL_ID bigint references FILE(FILE_ID) on delete cascade,
	WIDTH integer,
	HEIGHT integer,
	THUMBNAIL_ID bigint references FILE(FILE_ID) on delete cascade,
	primary key (ORIGINAL_ID, WIDTH, HEIGHT)
);


/**
 * Статус пользователя: действующий, забаненный и др.
 */
create sequence status_id_gen;
create table USER_STATUS (
	STATUS_ID integer primary key default nextval('status_id_gen'),
	NAME varchar(256) not null,
	SYSNAME varchar(256) not null unique,
	DESCRIPTION varchar(32000)
);
insert into USER_STATUS(STATUS_ID, SYSNAME, NAME)
values(1, 'active', 'Активный'), (2, 'banned', 'Забаненный');

/**
 * Подразделение сотрудников (корпорация, филиал, отдел)
 */
create sequence department_id_gen;
create table DEPARTMENT (
	DEPARTMENT_ID integer primary key default nextval('department_id_gen'),
	NAME varchar(256) not null,
	SYSNAME varchar(256) not null unique,
	DESCRIPTION varchar(32000),
	--Основная БД Oracle
	DEFAULT_SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID) on delete set null,
	--Основной сервер с БД для чата
	MAIN_SERVER_ID integer references APPSERVER (APPSERVER_ID) on delete set null
);

/**
 * Клиент компании Караби.
 * Может иметь доступ к различным базам данных, при входе в базу создаётся запись USER_LOGON
 */
create sequence user_id_gen;
create table CARABI_USER (
	USER_ID bigint primary key default nextval('user_id_gen'),
	LOGIN varchar(256) not null unique, --логин
	PASSWORD varchar(64) not null default '==', --зашифрованный пароль
	FIRSTNAME varchar(1024), --имя
	MIDDLENAME varchar(1024), --отчество
	LASTNAME varchar(1024), --фамилия
	EMAIL varchar(1024), --фамилия
	DEPARTMENT_ID integer references DEPARTMENT (DEPARTMENT_ID) on delete set null,
	ROLE varchar(1024), --описание роли в компании/системе
	DEPARTMENT varchar(1024), --подразделение
	--Основная БД Oracle
	DEFAULT_SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID) on delete set null,
	--основной сервер с БД для чата
	MAIN_SERVER_ID integer references APPSERVER (APPSERVER_ID) on delete set null,
	AVATAR bigint references FILE (FILE_ID), --файл с аватаром
	LASTACTIVE timestamp, --время последней активности
	SHOW_ONLINE integer default 1, --при нулевом значении не показывать онлайн (в чате и др.)
	STATUS_ID integer references USER_STATUS(STATUS_ID) on delete set null default 1
);

/**
 * Взаимосвязь пользователей (контакт-листы, друзья)
 */
create sequence user_relation_id_gen;
create table USER_RELATION (
	USER_RELATION_ID bigint primary key default nextval('user_relation_id_gen'),
	MAIN_USER_ID bigint not null references CARABI_USER (USER_ID) on delete cascade,-- у кого в кругах
	RELATED_USER_ID bigint not null references CARABI_USER (USER_ID) on delete cascade,-- кто в кругах
	unique(MAIN_USER_ID, RELATED_USER_ID)
);

/**
 * Тип пользовательской взаимосвязи
 */
create sequence relation_type_id_gen;
create table USER_RELATION_TYPE (
	RELATION_TYPE_ID integer primary key default nextval('relation_type_id_gen'),
	NAME varchar(1024) not null unique,
	SYSNAME varchar(1024) not null unique,
	DESCRIPTION varchar(32000) --Описание, если требуется
);

create table RELATION_HAS_TYPE (
	USER_RELATION_ID bigint,
	RELATION_TYPE_ID integer,
	primary key(USER_RELATION_ID, RELATION_TYPE_ID)
);

/**
 * Право пользователя (например, редактировать других пользователей)
 */
create sequence permission_id_gen;
create table USER_PERMISSION (
	PERMISSION_ID integer primary key default nextval('permission_id_gen'),
	NAME varchar(256) not null,
	SYSNAME varchar(256) not null unique,
	DESCRIPTION varchar(32000),
	PARENT_PERMISSION integer references USER_PERMISSION (PERMISSION_ID),
	ALLOWED_BY_DEFAULT boolean default false
);
insert into USER_PERMISSION(NAME, SYSNAME) values('Редактирование расширений чата', 'EDIT_CHAT_MESSAGE_TYPES');

/**
 * Группа пользователей с одинаковыми правами
 */
create sequence role_id_gen;
create table USER_ROLE (
	ROLE_ID integer primary key default nextval('role_id_gen'),
	NAME varchar(256) not null,
	SYSNAME varchar(256) not null unique,
	DESCRIPTION varchar(32000)
);

create table USER_HAS_PERMISSION (
	USER_ID bigint not null references CARABI_USER (USER_ID) on delete cascade,
	PERMISSION_ID integer not null references USER_PERMISSION (PERMISSION_ID),
	PERMISSION_INHIBITED boolean default false,
	primary key (USER_ID, PERMISSION_ID)
);

create table ROLE_HAS_PERMISSION (
	ROLE_ID bigint not null references USER_ROLE (ROLE_ID) on delete cascade,
	PERMISSION_ID integer not null references USER_PERMISSION (PERMISSION_ID),
	PERMISSION_INHIBITED boolean default false,
	primary key (ROLE_ID, PERMISSION_ID)
);

create table USER_HAS_ROLE (
	USER_ID bigint not null references CARABI_USER (USER_ID) on delete cascade,
	ROLE_ID bigint not null references USER_ROLE (ROLE_ID),
	primary key (USER_ID, ROLE_ID)
);

/**
 * Число входов пользователя на каждый сервер.
 * Если число входов на некоторый сервер больше, чем на 
 * указанный в CARABI_USER.MAIN_SERVER_ID -- возможно, его следует сделать основным
 * и перенести на него данные.
 */
create table USER_AT_SERVER_ENTER (
	USER_ID bigint references CARABI_USER(USER_ID) on delete cascade,
	SERVER_ID integer references APPSERVER (APPSERVER_ID) on delete cascade,
	NUMBER_OF_ENTERS bigint default 0,
	primary key (USER_ID, SERVER_ID)
);
/*insert into CARABI_USER (LOGIN, FIRSTNAME, MIDDLENAME, LASTNAME, DEFAULT_SCHEMA_ID) values
	('kop', 'Александр', 'Дмитриевич', 'Копилов', 1),
;*/

create table PERSONAL_TEMPORARY_CODE (
	TEMPORARY_CODE varchar(128) primary key,
	USER_ID bigint references CARABI_USER(USER_ID) on delete cascade,
	EXPIRATION_DATE timestamp,
	CODE_TYPE varchar(128)
);
create index TEMPORARY_CODE_TYPE on PERSONAL_TEMPORARY_CODE(CODE_TYPE);

/**
 *Схемы, к которым клиент имеет право подключаться
 */
create table ALLOWED_SCHEMAS (
	SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID) on delete cascade,
	USER_ID bigint references CARABI_USER (USER_ID) on delete cascade,
	primary key(SCHEMA_ID, USER_ID)
);
/*insert into ALLOWED_SCHEMAS (SCHEMA_ID, USER_ID) values
	(1, 1), (1, 2)
;*/

/**
 *Тип телефона
 */
create sequence phone_type_id_gen;
create table PHONE_TYPE (
	PHONE_TYPE_ID integer primary key default nextval('phone_type_id_gen'),
	NAME varchar(256),
	SYSNAME varchar(256) unique not null
);
insert into PHONE_TYPE (PHONE_TYPE_ID, NAME, SYSNAME) values
	(1, 'IP-телефон', 'SIP'), (2, 'Мобильный', 'mobile'), (3, 'Городской', 'simple')
;

create sequence phone_id_gen;
create table PHONE (
	PHONE_ID bigint primary key default nextval('phone_id_gen'),
	PHONE_TYPE integer references PHONE_TYPE (PHONE_TYPE_ID),
	OWNER_ID bigint references CARABI_USER(USER_ID),
	ORDERNUMBER integer,
	COUNTRY_CODE integer,
	REGION_CODE integer,
	MAIN_NUMBER bigint not null,
	SUFFIX INTEGER,
	SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID) --Только для sip: ID схемы БД, через которую осуществляется управление АТС
);

/**
 * Авторизация клиента компании Караби в определённой базе Oracle.
 * 
 */
create table USER_LOGON (
	TOKEN varchar(64) primary key, --сессионный ключ
	USER_ID bigint references CARABI_USER(USER_ID), --пользователь по ядровой базе
	ORACLE_USER_ID bigint, --ID пользователя в Oracle, к которому подключились
	DISPLAY varchar(1024), --имя
	IP_ADDR_WHITE varchar(64), --IP клиента, определённый сервером (внешний) -- для журналирования.
	IP_ADDR_GREY varchar(64), --IP клиента, переданный клиентом (внутренний) -- для журналирования.
	SERVER_CONTEXT varchar(64), --сервер, в котором открыта сессия -- например, 83.243.75.4/carabiserver
	LASTACTIVE timestamp,
	REQUIRESESSION integer,
	SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID),
	APPSERVER_ID integer references APPSERVER (APPSERVER_ID),
	PERMANENT integer default null -- постоянные записи -- для программных клиентов
);
create index USER_LOGON_LASTACTIVE on USER_LOGON(LASTACTIVE);
create index USER_LOGON_PERMANENT on USER_LOGON(PERMANENT);

insert into USER_LOGON (TOKEN, LASTACTIVE, PERMANENT)
values ('durfvber74fvqi3447qiviq4vfi73vfdzjycyew673i7q3', '1970-01-01 00:00:00.0', 1);

--select * from SYSCS_DIAG.LOCK_TABLE

/**
 * Категории хранимых запросов
 */
create sequence category_id_gen;
create table QUERY_CATEGORY (
	CATEGORY_ID integer primary key default nextval('category_id_gen'),
	NAME varchar(1024) not null unique, --название категории
	DESCRIPTION varchar(32000) --Описание
);

/**
 * SQL-запросы и PL-скрипты для Oracle.
 * Инициализируются пользователем при разработке, в служебную БД записываются с
 * заменой имён входных и выходных параметров на вопросы и сохренением имён,
 * порядка и типа параметров в таблице ORACLE_PARAMETER
 */
create sequence query_id_gen;
create table ORACLE_QUERY (
	QUERY_ID bigint primary key default nextval('query_id_gen'),
	IS_EXECUTABLE integer not null, --0 -- SQL-запрос (select), 1 -- исполняемый скрипт
	NAME varchar(1024) not null unique, --Имя, используемое администратором системы
	SYSNAME varchar(1024) not null unique, --Имя, по которому запрос будет вызываться клиентом
	CATEGORY_ID integer references QUERY_CATEGORY (CATEGORY_ID),
	--CATEGORY varchar(256), --Название категории для пользователей-администраторов БД 
	SQL_QUERY varchar(32000) not null, --SQL текст запроса
	COUNT_QUERY varchar(32000), --Запрос, возвращающий объём выборки основного запроса
	SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID) --ID схемы БД, для которой предназначен запрос
);

create sequence parameter_id_gen;
create table ORACLE_PARAMETER (
	PARAMETER_ID bigint primary key default nextval('parameter_id_gen'),
	NAME varchar(64) not null, --Название параметра
	TYPE_NAME varchar(64) not null, --Название его типа в БД (varchar,number,date,cursor...)
	IS_IN integer not null, --Является входным, если не 0
	IS_OUT integer not null, --Является выходным, если не 0 
	ORDERNUMBER integer not null, --Порядковый номер
	QUERY_ID bigint not null references ORACLE_QUERY(QUERY_ID) on delete cascade --ID запроса, для корого используется данный параметр
);

/**
 * Пользовательское программное обеспечение и модули
 */
create sequence production_id_gen;
create table SOFTWARE_PRODUCTION (
	PRODUCTION_ID integer primary key default nextval('production_id_gen'),
	NAME varchar(1024) not null unique, --Название продукта
	SYSNAME varchar(1024) not null unique, --Системное наименование
	DESCRIPTION varchar(32000), --Описание
	PARENT_PRODUCTION integer references SOFTWARE_PRODUCTION(PRODUCTION_ID),
	HOME_URL varchar(1024),
	SCHEMA_INDEPENDENT boolean, --работает на любых БД (или не использует БД) -- не используется фильтрация по PRODUCT_ON_SCHEMA
	APPSERVER_INDEPENDENT boolean, --работает на любых серверах (или не использует сервер) -- не используется фильтрация по PRODUCT_ON_APPSERVER
	VISIBLE boolean, --отображать среди доступных для непосредственного использования
	PERMISSION_TO_USE integer references USER_PERMISSION (PERMISSION_ID) -- право пользования продуктом
);

/**
 * Доступность/работоспособность продукта на схеме БД
 */
create table PRODUCT_ON_SCHEMA (
	PRODUCT_ID integer not null references SOFTWARE_PRODUCTION(PRODUCTION_ID) on delete cascade,
	SCHEMA_ID integer references CONNECTION_SCHEMA (SCHEMA_ID) on delete cascade,
	primary key (PRODUCT_ID, SCHEMA_ID)
);

/**
 * Доступность/работоспособность продукта на прикладном сервере
 */
create table PRODUCT_ON_APPSERVER (
	PRODUCT_ID integer not null references SOFTWARE_PRODUCTION(PRODUCTION_ID) on delete cascade,
	APPSERVER_ID integer references APPSERVER (APPSERVER_ID) on delete cascade,
	primary key (PRODUCT_ID, SCHEMA_ID)
);

/**
 * Версии продуктов Караби
 */
create sequence product_version_id_gen;
create table PRODUCT_VERSION (
	PRODUCT_VERSION_ID bigint primary key default nextval('product_version_id_gen'),
	PRODUCT_ID integer not null references SOFTWARE_PRODUCTION(PRODUCTION_ID) on delete cascade, --Версия какого продукта
	VERSION_NUMBER varchar(64) not null, --Номер версии вида '1.5.2.6'
	ISSUE_DATE date, --Дата выпуска
	SINGULARITY varchar(32000), --Особенности данной версии
	DOWNLOAD_URL varchar(1024), --Где скачать
	IS_SIGNIFICANT_UPDATE integer not null default 0,--Является важным обновлением, если не 0
	DESTINATED_FOR_DEPARTMENT integer references DEPARTMENT(DEPARTMENT_ID) on delete cascade --компания, которой адресована данная сборка
);

/**
 * Типы сообщений чата (служебные)
 */
create sequence extension_type_id_gen;
create table MESSAGE_EXTENSION_TYPE (
	EXTENSION_TYPE_ID integer primary key default nextval('extension_type_id_gen'),
	NAME varchar(256) not null,
	SYSNAME varchar(256) not null unique,
	DESCRIPTION varchar(32000),
	CONTENT_TYPE varchar(256)
);
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

/**
 * Группы сообщений чата: многопользовательские беседы, "стены"
 */
create sequence messages_group_id_gen;
create table MESSAGES_GROUP (
	MESSAGES_GROUP_ID integer primary key default nextval('messages_group_id_gen'),
	NAME varchar(256) not null,
	SYSNAME varchar(256) not null unique,
	DESCRIPTION varchar(32000),
	SERVER_ID integer references APPSERVER (APPSERVER_ID)
);

--commit;
