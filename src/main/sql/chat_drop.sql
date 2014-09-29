--connect 'jdbc:derby://localhost/carabiderby;user=carabi;password=carabipassword';
set schema = CARABI;

drop table CHAT_MESSAGE ;
drop table FILE;

drop schema CARABI restrict;
--commit;
