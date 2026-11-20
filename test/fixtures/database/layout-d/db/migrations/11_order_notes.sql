--liquibase formatted sql

--changeset orders-team:11-order-notes
CREATE TABLE order_notes (
  order_id NUMBER(19)     NOT NULL,
  note     VARCHAR2(4000) NOT NULL
);
--rollback DROP TABLE order_notes;
