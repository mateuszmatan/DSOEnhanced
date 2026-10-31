CREATE OR REPLACE PACKAGE ut_pkg_orders AS
  --%suite(Orders package)
  --%suitepath(orders)

  --%test(Total of a customer without orders is zero)
  PROCEDURE total_without_orders;

  --%test(Adding an order raises the total)
  PROCEDURE add_order_raises_total;
END ut_pkg_orders;
/
