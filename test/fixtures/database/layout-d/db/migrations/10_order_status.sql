-- Orders get a status; the old rows are NEW.
ALTER TABLE orders ADD (status VARCHAR2(20) DEFAULT 'NEW' NOT NULL);
