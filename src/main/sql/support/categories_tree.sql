--set SEARCH_PATH to CARABI_KERNEL;

--переопределить ограничения для таблицы query_category

--alter table carabi_kernel.query_category drop constraint query_category_parent_id_fkey;
--alter table carabi_kernel.query_category drop constraint query_category_name_key;

--alter table carabi_kernel.query_category 
--add constraint query_category_name_parent_id_key unique (name, parent_id);

--alter table carabi_kernel.query_category 
--add constraint query_category_parent_fkey 
--foreign key (parent_id) references carabi_kernel.query_category (category_id) MATCH FULL;

--добавить предопределенные категории Все, Результаты поиска и Удаленные
--insert into carabi_kernel.query_category
--values (default, 'Все', 'Все запросы из всех категорий', null);
--insert into carabi_kernel.query_category
--values (default, 'Результаты поиска', 'Результаты поиска запросов', null);
--insert into carabi_kernel.query_category
--values (default, 'Удаленные', 'Удаленные запросы', null);

--обновить parent_id в query_category так чтобы он указывал на 'Все' у всех категорий, кроме 'Все' и 'Удаленные'
--update carabi_kernel.query_category 
--set parent_id=(select category_id from carabi_kernel.query_category where name = 'Все') 
--where name <> 'Все' and name <> 'Удаленные';	
	--select * from carabi_kernel.query_category;

--обновить запросы (oracle_query) так чтобы у deprecated категория была с именем 'Удаленные'
--update carabi_kernel.oracle_query 
--set category_id=(select category_id from carabi_kernel.query_category where name = 'Удаленные' and parent_id is null) 
--where is_deprecated=true;
	--select * from carabi_kernel.oracle_query where is_deprecated=true;	

	--delete from carabi_kernel.oracle_query where category_id=(select category_id from carabi_kernel.query_category where name = 'Удаленные' and parent_id is null) ;

--select * from carabi_kernel.oracle_query

--select count(*)
--from carabi_kernel.oracle_query
--where name like '%'+'GET'+'%' --LOWER(name) like LOWER('GET') --or LOWER(sysname) like LOWER('GET') 
--order by name