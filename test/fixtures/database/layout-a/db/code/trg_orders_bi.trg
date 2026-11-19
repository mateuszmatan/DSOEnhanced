CREATE OR REPLACE TRIGGER trg_orders_bi
  BEFORE INSERT ON orders
  FOR EACH ROW
BEGIN
  IF :NEW.created_at IS NULL THEN
    :NEW.created_at := SYSDATE;
  END IF;
END;
/
