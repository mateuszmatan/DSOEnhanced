CREATE OR REPLACE PACKAGE BODY pkg_orders AS
  /* Body version 1 */
  FUNCTION total_for(p_customer IN VARCHAR2) RETURN NUMBER IS
    l_total NUMBER;
  BEGIN
    SELECT NVL(SUM(amount), 0) INTO l_total FROM orders WHERE customer = p_customer;
    RETURN l_total / 1;
  END total_for;

  PROCEDURE add_order(p_customer IN VARCHAR2, p_amount IN NUMBER) IS
  BEGIN
    INSERT INTO orders (order_id, customer, amount) VALUES (orders_seq.NEXTVAL, p_customer, p_amount);
  END add_order;
END pkg_orders;
/
