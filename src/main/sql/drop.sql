connect 'jdbc:derby://localhost/carabiderby;user=carabi;password=carabipassword';
set schema = CARABI;

drop table CARABI_PRODUCT_VERSION;
drop table CARABI_PRODUCTION;
drop table ORACLE_PARAMETER;
drop table ORACLE_QUERY;
drop table QUERY_CATEGORY;
drop table USER_LOGON;
drop table ALLOWED_SCHEMAS;
drop table USER_AT_SERVER_ENTER;
drop table CARABI_USER;
drop table FILE;
drop table APPSERVER;
drop table CONNECTION_SCHEMA;

drop schema CARABI restrict;
commit;
