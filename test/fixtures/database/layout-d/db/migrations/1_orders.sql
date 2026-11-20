CREATE SEQUENCE orders_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE orders (
  order_id   NUMBER(19)    NOT NULL,
  customer   VARCHAR2(100) NOT NULL,
  amount     NUMBER(12, 2) NOT NULL,
  created_at DATE,
  CONSTRAINT orders_pk PRIMARY KEY (order_id)
);

CREATE INDEX orders_customer_idx ON orders (customer);
