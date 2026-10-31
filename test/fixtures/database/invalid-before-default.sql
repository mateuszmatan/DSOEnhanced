SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_BEFORE FROM user_objects WHERE status = 'INVALID' ORDER BY 1
/
