--liquibase formatted sql

--changeset orders-team:002-create-order-items
CREATE TABLE order_items (
  order_id  NUMBER(19)   NOT NULL,
  line_no   NUMBER(5)    NOT NULL,
  sku       VARCHAR2(40) NOT NULL,
  qty       NUMBER(9)    NOT NULL,
  CONSTRAINT order_items_pk PRIMARY KEY (order_id, line_no),
  CONSTRAINT order_items_fk FOREIGN KEY (order_id) REFERENCES orders (order_id)
);
--rollback DROP TABLE order_items;
