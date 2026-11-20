CREATE TABLE order_items (
  order_id  NUMBER(19)   NOT NULL,
  line_no   NUMBER(5)    NOT NULL,
  product   VARCHAR2(50) NOT NULL,
  quantity  NUMBER(9)    NOT NULL,
  CONSTRAINT order_items_pk PRIMARY KEY (order_id, line_no),
  CONSTRAINT order_items_order_fk FOREIGN KEY (order_id) REFERENCES orders (order_id)
);
