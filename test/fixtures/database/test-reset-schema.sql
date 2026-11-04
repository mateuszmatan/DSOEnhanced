BEGIN
  FOR o IN (SELECT owner, object_name, object_type FROM all_objects WHERE owner = 'ORDERS_OWNER' AND object_type IN ('TRIGGER', 'PACKAGE', 'PROCEDURE', 'FUNCTION', 'SYNONYM', 'TYPE')
            AND object_name NOT LIKE 'BIN$%' ORDER BY DECODE(object_type, 'TRIGGER', 1, 'TYPE', 3, 2), object_name) LOOP
    BEGIN
      EXECUTE IMMEDIATE 'DROP ' || o.object_type || ' "' || o.owner || '"."' || o.object_name || '"' || CASE o.object_type WHEN 'TYPE' THEN ' FORCE' END;
    EXCEPTION
      WHEN OTHERS THEN
        IF SQLCODE <> -4043 THEN
          RAISE;
        END IF;
    END;
  END LOOP;
END;
/
