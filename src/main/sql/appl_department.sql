DROP SCHEMA IF EXISTS appl_department CASCADE;
CREATE SCHEMA appl_department;

/**
 * Возвращает ID подразделения по его системному имени.
 */
CREATE OR REPLACE FUNCTION appl_department.get_department_by_sysname(sysname$ CHARACTER VARYING)
	RETURNS INTEGER AS
$BODY$
DECLARE
	department_id$ INTEGER;
BEGIN
	SELECT department_id INTO department_id$ FROM carabi_kernel.DEPARTMENT WHERE sysname = sysname$;
	IF department_id$ IS NULL THEN
		RAISE EXCEPTION 'Unknown department: %', sysname$;
	END IF;
	RETURN department_id$;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Возвращает основное подразделение текущего пользователя
 */
CREATE OR REPLACE FUNCTION appl_department.get_department_by_token(token$ CHARACTER VARYING)
	RETURNS INTEGER AS
$BODY$
DECLARE
	department_id$ INTEGER;
BEGIN
	SELECT department_id INTO department_id$ FROM carabi_kernel.user_logon
			LEFT JOIN carabi_kernel.carabi_user ON carabi_kernel.user_logon.user_id = carabi_kernel.carabi_user.user_id
			WHERE token = token$;
	RETURN department_id$;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Возвращает все вышестоящие подразделения, включая данное.
 */
CREATE OR REPLACE FUNCTION appl_department.get_departments_branch(department_id$ INTEGER)
	RETURNS SETOF INTEGER AS
$BODY$
BEGIN
	RETURN QUERY
	WITH RECURSIVE tree (sysname, department_id, parent_department_id) AS (
		--текущее подразделение
		SELECT sysname, department_id, PARENT_DEPARTMENT_ID
		FROM carabi_kernel.department
		WHERE department_id = department_id$
			UNION ALL
		--родительское подразделение текущего и т.д.
		SELECT carabi_kernel.department.sysname, carabi_kernel.department.department_id, carabi_kernel.department.parent_department_id
		FROM carabi_kernel.department
		INNER JOIN tree ON tree.parent_department_id = carabi_kernel.department.department_id
	)
	SELECT department_id FROM tree;
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;

/**
 * Возвращает все вышестоящие подразделения, включая пользовательское или иное указанное.
 */
CREATE OR REPLACE FUNCTION appl_department.get_departments_branch(token$ CHARACTER VARYING, department_name$ CHARACTER VARYING)
	RETURNS SETOF INTEGER AS
$BODY$
DECLARE
	department_id$ INTEGER;
BEGIN
	IF department_name$ IS NULL OR department_name$ = '' THEN
		department_id$ := appl_department.get_department_by_token(token$);
	ELSE
		department_id$ := appl_department.get_department_by_sysname(department_name$);
	END IF;
	
	RETURN QUERY SELECT * FROM appl_department.get_departments_branch(department_id$);
END;
$BODY$
	LANGUAGE plpgsql VOLATILE;
