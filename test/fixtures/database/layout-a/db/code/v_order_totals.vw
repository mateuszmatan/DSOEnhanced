CREATE OR REPLACE VIEW v_order_totals AS
  SELECT customer, SUM(amount) AS total_amount
    FROM orders
   GROUP BY customer;
