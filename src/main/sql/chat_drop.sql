set SEARCH_PATH to CARABI_CHAT;

drop table CHAT_MESSAGE;
drop table FILE;
drop view DUAL;

drop sequence message_id_gen;
drop sequence file_id_gen;

drop schema CARABI_CHAT restrict;
--commit;
