connect 'jdbc:derby://localhost/carabiderby;user=carabi;password=carabipassword';
set schema = CARABI;

drop table CARABI_PRODUCT_VERSION;
drop table CARABI_PRODUCTION;
drop table ORACLE_PARAMETER;
drop table ORACLE_QUERY;
drop table QUERY_CATEGORY;
drop table USER_LOGON;
drop table PHONE;
drop table PHONE_TYPE;
drop table ALLOWED_SCHEMAS;
drop table USER_AT_SERVER_ENTER;
drop table RELATION_HAS_TYPE;
drop table USER_RELATION_TYPE;
drop table USER_RELATION;
drop table CARABI_USER;
drop table THUMBNAIL;
drop table FILE;
drop table APPSERVER;
drop table CONNECTION_SCHEMA;
drop table DUAL;

drop schema CARABI restrict;
commit;
