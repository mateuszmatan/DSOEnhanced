SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_UPDATED FROM user_objects WHERE status = 'INVALID' ORDER BY 1
/
SELECT object_type || ' ' || object_name AS INVALID_OBJECT FROM user_objects WHERE status = 'INVALID' ORDER BY 1
/
SELECT type || ' ' || name || ' line ' || line || ': ' || REPLACE(text, CHR(10), ' ') AS COMPILE_ERROR FROM user_errors WHERE attribute = 'ERROR' ORDER BY type, name, sequence
/
