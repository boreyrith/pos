-- Price overrides at checkout. Each order line keeps the catalog price it would have sold at,
-- whether the cashier changed it, why, and who. Admins get both new permissions; managers may
-- override prices but not sell below cost. Other roles keep the catalog price until given them.
ALTER TABLE order_items
    ADD COLUMN list_price            DECIMAL(12, 2) NULL AFTER unit_price,
    ADD COLUMN price_overridden      BOOLEAN        NOT NULL DEFAULT FALSE AFTER list_price,
    ADD COLUMN price_override_reason VARCHAR(255)   NULL AFTER price_overridden,
    ADD COLUMN price_override_by     BIGINT         NULL AFTER price_override_reason,
    ADD CONSTRAINT fk_item_override_by FOREIGN KEY (price_override_by) REFERENCES users (id);
UPDATE order_items SET list_price = unit_price;
ALTER TABLE order_items MODIFY list_price DECIMAL(12, 2) NOT NULL;

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r JOIN (
    SELECT 'ADMIN' AS code, 'PRICE_OVERRIDE' AS permission
    UNION ALL SELECT 'ADMIN', 'PRICE_BELOW_COST'
    UNION ALL SELECT 'MANAGER', 'PRICE_OVERRIDE'
) p ON p.code = r.code;
