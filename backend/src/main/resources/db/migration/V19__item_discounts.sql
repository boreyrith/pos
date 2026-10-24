-- Item discounts: a % or amount off one order line, before the order discount and tax. Who may
-- give them is the "Discount items" permission, up to the role's maximum % (null = no limit).
ALTER TABLE order_items
    ADD COLUMN item_discount_type  VARCHAR(10)    NULL AFTER line_total,
    ADD COLUMN item_discount_value DECIMAL(12, 2) NULL AFTER item_discount_type,
    -- The money it took off the line (part of line_discount, which also holds the order discount share).
    ADD COLUMN item_discount       DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER item_discount_value;

ALTER TABLE orders
    -- Item discounts on all lines; discount_amount stays the order discount.
    ADD COLUMN item_discount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER discount_value;

ALTER TABLE roles
    ADD COLUMN max_item_discount_pct DECIMAL(5, 2) NULL AFTER super_admin,
    ADD CONSTRAINT ck_role_max_discount CHECK (max_item_discount_pct IS NULL OR max_item_discount_pct BETWEEN 0 AND 100);

INSERT INTO role_permissions (role_id, permission)
SELECT id, 'DISCOUNT_ITEMS' FROM roles WHERE code IN ('ADMIN', 'MANAGER');
UPDATE roles SET max_item_discount_pct = 20 WHERE code = 'MANAGER';
