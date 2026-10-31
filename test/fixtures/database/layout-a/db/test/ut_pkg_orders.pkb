CREATE OR REPLACE PACKAGE BODY ut_pkg_orders AS
  PROCEDURE total_without_orders IS
  BEGIN
    ut.expect(pkg_orders.total_for('nobody')).to_equal(0);
  END total_without_orders;

  PROCEDURE add_order_raises_total IS
  BEGIN
    pkg_orders.add_order('alice', 10);
    ut.expect(pkg_orders.total_for('alice')).to_equal(10);
  END add_order_raises_total;
END ut_pkg_orders;
/
