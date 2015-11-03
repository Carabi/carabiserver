alter table  carabi_kernel.USER_PERMISSION
add column 
PERMISSION_TO_ASSIGN integer references USER_PERMISSION (PERMISSION_ID);
