--liquibase formatted sql

--changeset orders-team:001-create-orders-seq
--comment: Sequence for order ids
CREATE SEQUENCE orders_seq START WITH 1 INCREMENT BY 1;
--rollback DROP SEQUENCE orders_seq;

--changeset orders-team:001-create-orders
CREATE TABLE orders (
  order_id    NUMBER(19)    NOT NULL,
  customer    VARCHAR2(100) NOT NULL,
  amount      NUMBER(12,2)  DEFAULT 0 NOT NULL,
  created_at  DATE,
  CONSTRAINT orders_pk PRIMARY KEY (order_id)
);
--rollback DROP TABLE orders;

--changeset orders-team:001-create-orders-idx
CREATE INDEX orders_customer_ix ON orders (customer);
--rollback DROP INDEX orders_customer_ix;
