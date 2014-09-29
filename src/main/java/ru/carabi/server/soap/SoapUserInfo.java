package ru.carabi.server.soap;

import java.util.ArrayList;

/**
 * Информация о пользователе для отправки через web-сервис
 * @author sasha
 */
public class SoapUserInfo {
	
	public String token; // 0 авторизационный токен
	public int ct_item_id; // 1
	public String display; // 2
	public int owner; // 3 - client/root
	public String owner_fullname; // 4
	public int parent; // 5 department/client/root
	public String parent_display; // 6
	public int role; // 7 contact_role code
	public String role_descr; // 8 contact_role value
	public ArrayList available_workspaces; // 9
	public String databaseDescr; // 10
	public int userrole_id; // 11
	public String license_to; // 12
}
