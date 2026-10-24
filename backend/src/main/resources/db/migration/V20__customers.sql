-- Customers. Every order belongs to one; the built-in "Walk-in Customer" (default) is used when the
-- cashier doesn't pick anyone, and existing orders are attached to it. orders.customer_name stays
-- as the name at the time of sale (or a name typed for a walk-in).
CREATE TABLE customers (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(20)  NULL UNIQUE,
    name         VARCHAR(150) NOT NULL,
    -- Digits and a leading + only, so lookups match however it was typed.
    phone        VARCHAR(30)  NULL UNIQUE,
    email        VARCHAR(100) NULL,
    address      VARCHAR(255) NULL,
    tax_id       VARCHAR(50)  NULL,
    note         VARCHAR(500) NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Only set for the default customer, so the unique key allows exactly one.
    default_flag TINYINT AS (IF(is_default, 1, NULL)) STORED,
    created_by   BIGINT       NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_default_customer UNIQUE (default_flag),
    CONSTRAINT fk_customer_creator FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_customer_name (name)
);

INSERT INTO customers (code, name, is_default, note)
VALUES ('WALK-IN', 'Walk-in Customer', TRUE, 'General customer for sales to anyone not on file');

ALTER TABLE orders ADD COLUMN customer_id BIGINT NULL AFTER storage_id;
UPDATE orders SET customer_id = (SELECT id FROM customers WHERE is_default);
ALTER TABLE orders
    MODIFY customer_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    ADD INDEX idx_order_customer_id (customer_id, created_at);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r JOIN (
    SELECT 'ADMIN' AS code, 'CUSTOMERS_VIEW' AS permission UNION ALL SELECT 'ADMIN', 'CUSTOMERS_MANAGE'
    UNION ALL SELECT 'MANAGER', 'CUSTOMERS_VIEW' UNION ALL SELECT 'MANAGER', 'CUSTOMERS_MANAGE'
    UNION ALL SELECT 'CASHIER', 'CUSTOMERS_VIEW' UNION ALL SELECT 'CASHIER', 'CUSTOMERS_MANAGE'
    UNION ALL SELECT 'ACCOUNTANT', 'CUSTOMERS_VIEW'
) p ON p.code = r.code;
