-- Who an order was for, as the cashier typed it on the sell screen (name or phone). There is no
-- customer list yet; this keeps each held cart's customer on the order and receipt.
ALTER TABLE orders
    ADD COLUMN customer_name VARCHAR(100) NULL AFTER note,
    ADD INDEX idx_order_customer (customer_name);
