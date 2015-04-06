DROP SCHEMA appl_permissions cascade;
CREATE SCHEMA appl_permissions;

/**
 * Возвращает ID права по его системному имени.
 */
CREATE OR REPLACE FUNCTION appl_permissions.get_user_permission_by_sysname(sysname$ CHARACTER VARYING)
	RETURNS INTEGER AS
$BODY$
DECLARE
	permission_id$ INTEGER;
BEGIN
	SELECT permission_id INTO permission_id$ FROM carabi_kernel.user_permission WHERE sysname = sysname$;
	IF permission_id$ IS NULL THEN
		RAISE EXCEPTION 'Unknown permission: %', sysname$;
	END IF;
	RETURN permission_id$;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;


/**Проверка, имеет ли пользователь право. Возвращает true, если:
 * есть запись в user_has_permission с inhibited = 0 для этого пользователя и права;
 * есть запись в role_has_permission с inhibited = 0 для одной из ролей этого пользователя и данного права и нет аналогичной записи с inhibited = 1;
 * в самом праве allowed_by_default = 1.
 * Запрет возвращается сразу, разрешение проверяется по родительскому праву при наличии такового.
 * (Например, чтобы иметь право редактировать web-страницы, пользователь должен иметь право входить в CMS.)
 */
CREATE OR REPLACE FUNCTION appl_permissions.user_has_permission(user_id$ BIGINT, permission_sysname$ CHARACTER VARYING)
	RETURNS BOOLEAN AS
$BODY$
DECLARE
BEGIN
	RETURN appl_permissions.user_has_permission(user_id$, appl_permissions.get_user_permission_by_sysname(permission_sysname$));
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;


/**Проверка, имеет ли пользователь право. Возвращает true, если:
 * есть запись в user_has_permission с inhibited = 0 для этого пользователя и права;
 * есть запись в role_has_permission с inhibited = 0 для одной из ролей этого пользователя и данного права и нет аналогичной записи с inhibited = 1;
 * в самом праве allowed_by_default = 1.
 * Запрет возвращается сразу, разрешение проверяется по родительскому праву при наличии такового.
 * (Например, чтобы иметь право редактировать web-страницы, пользователь должен иметь право входить в CMS.)
 */
CREATE OR REPLACE FUNCTION appl_permissions.user_has_permission(user_id$ BIGINT, permission_id$ INTEGER)
	RETURNS BOOLEAN AS
$BODY$
DECLARE
	inhibited INTEGER;
	first_inhibited INTEGER;
	allowed_by_default INTEGER;
	permissions REFCURSOR;
	has_next INTEGER;
	error CHARACTER VARYING;
	has_permission BOOLEAN;
BEGIN
	--проверка права конкретно для пользователя
	OPEN permissions FOR
		SELECT 1 as has_next, permission_inhibited
		FROM carabi_kernel.user_has_permission
		WHERE user_id = user_id$ AND permission_id = permission_id$;
	FETCH permissions INTO has_next, inhibited;
	CLOSE permissions;
	IF has_next = 1 THEN
		--если запись есть -- возвращаем отсутствие запрета
		has_permission := inhibited = 0 OR inhibited is null;
		RETURN has_permission AND appl_permissions.user_has_parent_permission(user_id$, permission_id$);
	END IF;
	
	--проверка для ролей пользователя
	OPEN permissions FOR
		SELECT 1 as has_next, permission_inhibited
		FROM carabi_kernel.user_has_role INNER JOIN carabi_kernel.role_has_permission
		ON user_has_role.role_id = role_has_permission.role_id
		WHERE user_id = user_id$ AND permission_id = permission_id$;
	FETCH permissions INTO has_next, inhibited;
	first_inhibited := inhibited;
	WHILE has_next = 1 LOOP --для каждой роли должно быть указано или pазрешение, или запрет (или ничего)
		IF inhibited != first_inhibited THEN
			RAISE EXCEPTION 'Contradiction in roles for user % and permission %', user_id$, permission_id$;
		END IF;
		FETCH permissions INTO has_next, inhibited;	
	END LOOP;
	CLOSE permissions;
	IF first_inhibited IS NOT NULL THEN
		has_permission := first_inhibited = 0;
		RETURN has_permission AND appl_permissions.user_has_parent_permission(user_id$, permission_id$);
	END IF;

	--если ничего не указано -- берём значение по умолчанию
	OPEN permissions FOR
		SELECT 1 as has_next, user_permission.allowed_by_default FROM carabi_kernel.user_permission
		WHERE permission_id = permission_id$;
	FETCH permissions INTO has_next, allowed_by_default;
	CLOSE permissions;
	IF has_next = 1 THEN
		has_permission := allowed_by_default > 0;
		RETURN has_permission AND appl_permissions.user_has_parent_permission(user_id$, permission_id$);
	ELSE
		RAISE EXCEPTION 'Unknown permission id: %', permission_id$;
	END IF;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;


/**
 * Проверка, имеет ли пользователь право, лежащее над данным.
 * Для parent_permission, если оно есть, вызывается функция user_has_parent_permission.
 * Если нет -- возвращается true.
 */
CREATE OR REPLACE FUNCTION appl_permissions.user_has_parent_permission(user_id$ BIGINT, permission_id$ INTEGER)
	RETURNS BOOLEAN AS
$BODY$
DECLARE
	parent_permission$ INTEGER;
BEGIN
	SELECT parent_permission INTO parent_permission$ FROM carabi_kernel.user_permission WHERE permission_id = permission_id$;
	IF parent_permission$ IS NULL THEN
		RETURN TRUE;
	ELSE
		RETURN appl_permissions.user_has_permission(user_id$, parent_permission$);
	END IF;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;


CREATE TYPE appl_permissions.permission AS (permission_id INTEGER, name CHARACTER VARYING, sysname CHARACTER VARYING, parent_permission INTEGER);
/**
 * Возвращает все права пользователя
 */
CREATE OR REPLACE FUNCTION appl_permissions.get_user_permissions(user_id$ BIGINT)
--	RETURNS TABLE(permission_id INTEGER, name CHARACTER VARYING, sysname CHARACTER VARYING, parent_permission INTEGER) AS
	RETURNS SETOF appl_permissions.permission AS
$BODY$
DECLARE
	root_permissions REFCURSOR;
	has_next INTEGER;
	permission_id$ INTEGER;
	name$ CHARACTER VARYING;
	sysname$ CHARACTER VARYING;
	parent_permission$ INTEGER;
BEGIN
	OPEN root_permissions FOR
		SELECT 1 as has_next, permission_id, name, sysname, parent_permission
		FROM carabi_kernel.user_permission WHERE parent_permission IS NULL;
	FETCH root_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	WHILE has_next = 1 LOOP
		IF appl_permissions.user_has_permission(user_id$, permission_id$) THEN
			RETURN NEXT (permission_id$, name$, sysname$, parent_permission$);
			RETURN QUERY SELECT * FROM appl_permissions.get_user_permissions(user_id$, permission_id$);
		END IF;
		FETCH root_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	END LOOP;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;
/**
 * Возвращает все права пользователя ниже указанного
 */
CREATE OR REPLACE FUNCTION appl_permissions.get_user_permissions(user_id$ BIGINT, root_permission_id$ INTEGER)
--	RETURNS TABLE(permission_id INTEGER, name CHARACTER VARYING, sysname CHARACTER VARYING, parent_permission INTEGER) AS
	RETURNS SETOF appl_permissions.permission AS
$BODY$
DECLARE
	child_permissions REFCURSOR;
	has_next INTEGER;
	permission_id$ INTEGER;
	name$ CHARACTER VARYING;
	sysname$ CHARACTER VARYING;
	parent_permission$ INTEGER;
BEGIN
	OPEN child_permissions FOR
		SELECT 1 AS has_next, permission_id, name, sysname, parent_permission
		FROM carabi_kernel.user_permission WHERE parent_permission = root_permission_id$;
	FETCH child_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	WHILE has_next = 1 LOOP
		IF appl_permissions.user_has_permission(user_id$, permission_id$) THEN
			RETURN NEXT (permission_id$, name$, sysname$, parent_permission$);
			RETURN QUERY SELECT * FROM appl_permissions.get_user_permissions(user_id$, permission_id$);
		END IF;
		FETCH child_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	END LOOP;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;
