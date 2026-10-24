-- Cost of sales: each order line keeps what it cost at the time of sale, so profit and margin can
-- be reported. Orders made before this are estimated from today's catalog costs (products and
-- items: cost x quantity; services: labour x quantity x hours, plus the parts used; packages: what
-- was stocked inside). Plus the permissions for the Reports module.
ALTER TABLE order_items ADD COLUMN cost_total DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER line_paid;

UPDATE order_items i JOIN offerings o ON o.id = i.offering_id
SET i.cost_total = ROUND(
        CASE
            WHEN i.offering_type IN ('PRODUCT', 'ITEM') THEN o.cost * i.quantity
            WHEN i.offering_type = 'SERVICE' THEN o.cost * i.quantity * COALESCE(i.hours, 1)
            ELSE 0
        END
        + COALESCE((SELECT SUM(p.cost * c.quantity) FROM order_item_components c JOIN offerings p ON p.id = c.item_id
                    WHERE c.order_item_id = i.id), 0), 2);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r JOIN (
    SELECT 'ADMIN' AS code, 'REPORTS_SALES' AS permission UNION ALL SELECT 'ADMIN', 'REPORTS_INVENTORY'
    UNION ALL SELECT 'ADMIN', 'REPORTS_PURCHASING'
    UNION ALL SELECT 'MANAGER', 'REPORTS_SALES' UNION ALL SELECT 'MANAGER', 'REPORTS_INVENTORY'
    UNION ALL SELECT 'MANAGER', 'REPORTS_PURCHASING'
    UNION ALL SELECT 'ACCOUNTANT', 'REPORTS_SALES' UNION ALL SELECT 'ACCOUNTANT', 'REPORTS_INVENTORY'
    UNION ALL SELECT 'ACCOUNTANT', 'REPORTS_PURCHASING'
    UNION ALL SELECT 'INVENTORY_STAFF', 'REPORTS_INVENTORY'
) p ON p.code = r.code;
