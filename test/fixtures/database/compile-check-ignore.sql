SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_UPDATED FROM user_objects WHERE status = 'INVALID' AND object_name NOT IN ('V_LEGACY_REPORT','PKG_OLD') ORDER BY 1
/
BEGIN
  DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE, reuse_settings => TRUE);
END;
/
SELECT object_type || ' ' || object_name AS INVALID_OBJECT FROM user_objects WHERE status = 'INVALID' AND object_name NOT IN ('V_LEGACY_REPORT','PKG_OLD') ORDER BY 1
/
SELECT type || ' ' || name || ' line ' || line || ': ' || REPLACE(text, CHR(10), ' ') AS COMPILE_ERROR FROM user_errors WHERE attribute = 'ERROR' AND name NOT IN ('V_LEGACY_REPORT','PKG_OLD') ORDER BY type, name, sequence
/
