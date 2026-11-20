CREATE OR REPLACE PACKAGE pkg_orders AS
  -- Public API for orders. This comment must stay in USER_SOURCE.
  FUNCTION total_for(p_customer IN VARCHAR2) RETURN NUMBER;
  PROCEDURE add_order(p_customer IN VARCHAR2, p_amount IN NUMBER);
END pkg_orders;
/
