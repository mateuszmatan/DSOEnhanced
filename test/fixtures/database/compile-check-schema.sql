SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_UPDATED FROM all_objects WHERE owner = 'ORDERS_OWNER' AND status = 'INVALID' ORDER BY 1
/
BEGIN
  DBMS_UTILITY.COMPILE_SCHEMA(schema => 'ORDERS_OWNER', compile_all => FALSE, reuse_settings => TRUE);
END;
/
SELECT object_type || ' ' || object_name AS INVALID_OBJECT FROM all_objects WHERE owner = 'ORDERS_OWNER' AND status = 'INVALID' ORDER BY 1
/
SELECT type || ' ' || name || ' line ' || line || ': ' || REPLACE(text, CHR(10), ' ') AS COMPILE_ERROR FROM all_errors WHERE owner = 'ORDERS_OWNER' AND attribute = 'ERROR' ORDER BY type, name, sequence
/
