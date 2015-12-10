DROP SCHEMA IF EXISTS appl_permissions CASCADE;
CREATE SCHEMA appl_permissions;

CREATE TYPE appl_permissions.permission AS (permission_id INTEGER, name CHARACTER VARYING, sysname CHARACTER VARYING, parent_permission INTEGER);

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
 * есть запись в user_has_permission с inhibited = false для этого пользователя и права;
 * если нет, то:
 * есть запись в role_has_permission с inhibited = 0 для одной из ролей этого пользователя
 * и данного права и нет аналогичной записи с inhibited = 1;
 * если нет, то:
 * в самом праве стоит allowed_by_default = 1.
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
 * есть запись в user_has_permission с inhibited = false для этого пользователя и права;
 * есть запись в role_has_permission с inhibited = false для одной из ролей этого пользователя и данного права и нет аналогичной записи с inhibited = true;
 * в самом праве allowed_by_default = 1.
 * Запрет возвращается сразу, разрешение проверяется по родительскому праву при наличии такового.
 * (Например, чтобы иметь право редактировать web-страницы, пользователь должен иметь право входить в CMS.)
 */
CREATE OR REPLACE FUNCTION appl_permissions.user_has_permission(user_id$ BIGINT, permission_id$ INTEGER)
	RETURNS BOOLEAN AS
$BODY$
DECLARE
	inhibited BOOLEAN;
	first_inhibited BOOLEAN;
	allowed_by_default INTEGER;
	permissions REFCURSOR;
	has_next BOOLEAN;
	error CHARACTER VARYING;
	has_permission BOOLEAN;
BEGIN
	--проверка права конкретно для пользователя
	OPEN permissions FOR
		SELECT TRUE as has_next, permission_inhibited
		FROM carabi_kernel.user_has_permission
		WHERE user_id = user_id$ AND permission_id = permission_id$;
	FETCH permissions INTO has_next, inhibited;
	CLOSE permissions;
	IF has_next THEN
		--если запись есть -- возвращаем отсутствие запрета
		has_permission := inhibited = false OR inhibited is null;
		RETURN has_permission AND appl_permissions.user_has_parent_permission(user_id$, permission_id$);
	END IF;
	
	--проверка для ролей пользователя
	OPEN permissions FOR
		SELECT TRUE as has_next, permission_inhibited
		FROM carabi_kernel.user_has_role INNER JOIN carabi_kernel.role_has_permission
		ON user_has_role.role_id = role_has_permission.role_id
		WHERE user_id = user_id$ AND permission_id = permission_id$;
	FETCH permissions INTO has_next, inhibited;
	first_inhibited := inhibited;
	WHILE has_next LOOP --для каждой роли должно быть указано или pазрешение, или запрет (или ничего)
		IF inhibited != first_inhibited THEN
			RAISE EXCEPTION 'Contradiction in roles for user % and permission %', user_id$, permission_id$;
		END IF;
		FETCH permissions INTO has_next, inhibited;	
	END LOOP;
	CLOSE permissions;
	IF first_inhibited IS NOT NULL THEN
		has_permission := first_inhibited = false;
		RETURN has_permission AND appl_permissions.user_has_parent_permission(user_id$, permission_id$);
	END IF;

	--если ничего не указано -- берём значение по умолчанию
	OPEN permissions FOR
		SELECT TRUE as has_next, user_permission.allowed_by_default FROM carabi_kernel.user_permission
		WHERE permission_id = permission_id$;
	FETCH permissions INTO has_next, allowed_by_default;
	CLOSE permissions;
	IF has_next THEN
		has_permission := allowed_by_default;
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


/**
 * Возвращает все права, которые имеет пользователь
 */
CREATE OR REPLACE FUNCTION appl_permissions.get_user_permissions(user_id$ BIGINT)
--	RETURNS TABLE(permission_id INTEGER, name CHARACTER VARYING, sysname CHARACTER VARYING, parent_permission INTEGER) AS
	RETURNS SETOF appl_permissions.permission AS
$BODY$
DECLARE
	root_permissions REFCURSOR;
	has_next BOOLEAN;
	permission_id$ INTEGER;
	name$ CHARACTER VARYING;
	sysname$ CHARACTER VARYING;
	parent_permission$ INTEGER;
BEGIN
	OPEN root_permissions FOR
		SELECT TRUE as has_next, permission_id, name, sysname, parent_permission
		FROM carabi_kernel.user_permission WHERE parent_permission IS NULL;
	FETCH root_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	WHILE has_next LOOP
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
 * Возвращает все дочерние права пользователя под данным родительским
 */
CREATE OR REPLACE FUNCTION appl_permissions.get_user_permissions(user_id$ BIGINT, parent_permission_id$ INTEGER)
--	RETURNS TABLE(permission_id INTEGER, name CHARACTER VARYING, sysname CHARACTER VARYING, parent_permission INTEGER) AS
	RETURNS SETOF appl_permissions.permission AS
$BODY$
DECLARE
	child_permissions REFCURSOR;
	has_next BOOLEAN;
	permission_id$ INTEGER;
	name$ CHARACTER VARYING;
	sysname$ CHARACTER VARYING;
	parent_permission$ INTEGER;
BEGIN
	OPEN child_permissions FOR
		SELECT TRUE AS has_next, permission_id, name, sysname, parent_permission
		FROM carabi_kernel.user_permission WHERE parent_permission = parent_permission_id$;
	FETCH child_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	WHILE has_next LOOP
		IF appl_permissions.user_has_permission(user_id$, permission_id$) THEN
			RETURN NEXT (permission_id$, name$, sysname$, parent_permission$);
			RETURN QUERY SELECT * FROM appl_permissions.get_user_permissions(user_id$, permission_id$);
		END IF;
		FETCH child_permissions INTO has_next, permission_id$, name$, sysname$, parent_permission$;
	END LOOP;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Проверка, что конкретный пользователь может выдать или отнять конкретное право
 */
CREATE OR REPLACE FUNCTION appl_permissions.may_assign_permission(user_id$ BIGINT, permission_id$ INTEGER)
	RETURNS BOOLEAN AS
$BODY$
DECLARE
	permission_to_assign_id$ INTEGER;
	parent_permission_id$ INTEGER;
BEGIN
	SELECT permission_to_assign, parent_permission INTO permission_to_assign_id$, parent_permission_id$
	FROM carabi_kernel.user_permission WHERE permission_id = permission_id$;
	WHILE permission_to_assign_id$ IS NULL AND parent_permission_id$ IS NOT NULL LOOP
		SELECT permission_to_assign, parent_permission INTO permission_to_assign_id$, parent_permission_id$
		FROM carabi_kernel.user_permission WHERE permission_id = parent_permission_id$;
	END LOOP;
	IF permission_to_assign_id$ IS NULL THEN
		permission_to_assign_id$ := appl_permissions.get_user_permission_by_sysname('ADMINISTRATING-PERMISSIONS-ASSIGN');
	END IF;
	
	RETURN appl_permissions.user_has_permission(user_id$, permission_to_assign_id$);
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Проверка, что конкретный пользователь может выдать или отнять конкретное право
 */
CREATE OR REPLACE FUNCTION appl_permissions.may_assign_permission(user_id$ BIGINT, permission_sysname$ CHARACTER VARYING)
	RETURNS BOOLEAN AS
$BODY$
DECLARE
BEGIN
	RETURN appl_permissions.may_assign_permission(user_id$, appl_permissions.get_user_permission_by_sysname(permission_sysname$));
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Скопировать права из роли в пользователя, не привязывая роль
 * current_user_id$ -- id текущего пользователя, выполняющего действие
 * role_id$ -- id копируемой роли
 * user_id$ -- id редактируемого пользователя
 * remove_old_permossions$ -- если true, удалить у пользователя с user_id$ права,
 * которые current_user может назначать/изымать
 */
CREATE OR REPLACE FUNCTION appl_permissions.role_to_user(current_user_id$ BIGINT, role_id$ INTEGER, user_id$ BIGINT, remove_old_permossions$ BOOLEAN)
	RETURNS VOID AS
$BODY$
DECLARE
	permissions REFCURSOR;
	has_next BOOLEAN;
	permission_id$ INTEGER;
BEGIN
	IF remove_old_permossions$ THEN
		OPEN permissions FOR
			SELECT TRUE AS has_next, permission_id FROM carabi_kernel.user_has_permission WHERE user_id = user_id$;
		FETCH permissions INTO has_next, permission_id$;
		WHILE has_next LOOP
			IF appl_permissions.may_assign_permission(current_user_id$, permission_id$) THEN
				DELETE FROM carabi_kernel.user_has_permission WHERE user_id = user_id$ AND permission_id = permission_id$;
			END IF;
			FETCH permissions INTO has_next, permission_id$;
		END LOOP;
		CLOSE permissions;
	END IF;
	
	OPEN permissions FOR
		SELECT TRUE AS has_next, permission_id FROM carabi_kernel.role_has_permission WHERE role_id = role_id$;
	FETCH permissions INTO has_next, permission_id$;
	WHILE has_next LOOP
		IF appl_permissions.may_assign_permission(current_user_id$, permission_id$) THEN
			INSERT INTO carabi_kernel.user_has_permission (user_id, permission_id) VALUES (user_id$, permission_id$);
		ELSE
			RAISE 'User % can not assign permission %', current_user_id$, permission_id$;
		END IF;
		FETCH permissions INTO has_next, permission_id$;
	END LOOP;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Скопировать права из пользователя в роль (сделать роль шаблоном для назначения другим пользователям)
 * current_user_id$ -- id текущего пользователя, выполняющего действие
 * role_id$ -- id редактируемой роли
 * user_id$ -- id копируемого пользователя
 * remove_old_permossions$ -- если true, удалить у роли с role_id$ права,
 * которые current_user может назначать/изымать
 */
CREATE OR REPLACE FUNCTION appl_permissions.role_from_user(current_user_id$ BIGINT, role_id$ INTEGER, user_id$ BIGINT, remove_old_permossions$ BOOLEAN)
	RETURNS VOID AS
$BODY$
DECLARE
	permissions REFCURSOR;
	has_next BOOLEAN;
	permission_id$ INTEGER;
BEGIN
	IF remove_old_permossions$ THEN
		OPEN permissions FOR
			SELECT TRUE AS has_next, permission_id FROM carabi_kernel.role_has_permission WHERE role_id = role_id$;
		FETCH permissions INTO has_next, permission_id$;
		WHILE has_next LOOP
			IF appl_permissions.may_assign_permission(current_user_id$, permission_id$) THEN
				DELETE FROM carabi_kernel.role_has_permission WHERE role_id = role_id$ AND permission_id = permission_id$;
			END IF;
			FETCH permissions INTO has_next, permission_id$;
		END LOOP;
		CLOSE permissions;
	END IF;
	
	OPEN permissions FOR
		SELECT TRUE AS has_next, permission_id FROM carabi_kernel.user_has_permission WHERE user_id = user_id$;
	FETCH permissions INTO has_next, permission_id$;
	WHILE has_next LOOP
		IF appl_permissions.may_assign_permission(current_user_id$, permission_id$) THEN
			INSERT INTO carabi_kernel.role_has_permission (role_id, permission_id) VALUES (role_id$, permission_id$);
		ELSE
			RAISE 'User % can not assign permission %', current_user_id$, permission_id$;
		END IF;
		FETCH permissions INTO has_next, permission_id$;
	END LOOP;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;
