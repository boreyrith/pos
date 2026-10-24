-- Clears a DEVELOPMENT database of catalog, stock, purchasing, sales, customers and audit history,
-- so the dev seeder and seed-kids-store.py can fill it again. Keeps users, roles and permissions,
-- company settings, taxes, currencies and rates, units, branches and storages.
--
-- Never run this against production, and take a backup first (see scripts/demo-data/README.md).
-- Stop the API, run this, then start the API (the dev seeder loads the catalog):
--   docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" pos_next' < scripts/demo-data/reset-dev-data.sql

-- Guard: stop unless this is the local pos_next database ("Subquery returns more than 1 row").
SELECT IF(DATABASE() = 'pos_next', 1, (SELECT 1 UNION SELECT 2)) AS dev_database_check;

SET FOREIGN_KEY_CHECKS = 0;

-- Sales and payments
TRUNCATE TABLE held_sales;
TRUNCATE TABLE order_return_lines;
TRUNCATE TABLE order_returns;
TRUNCATE TABLE order_item_components;
TRUNCATE TABLE order_item_contents;
TRUNCATE TABLE order_taxes;
TRUNCATE TABLE order_rates;
TRUNCATE TABLE order_items;
TRUNCATE TABLE payment_tenders;
TRUNCATE TABLE payments;
TRUNCATE TABLE orders;

-- Purchasing
TRUNCATE TABLE vendor_payments;
TRUNCATE TABLE vendor_invoices;
TRUNCATE TABLE goods_receipt_lines;
TRUNCATE TABLE goods_receipts;
TRUNCATE TABLE purchase_order_lines;
TRUNCATE TABLE purchase_orders;
TRUNCATE TABLE vendors;

-- Stock
TRUNCATE TABLE stock_adjustment_lines;
TRUNCATE TABLE stock_adjustments;
TRUNCATE TABLE stock_transfer_lines;
TRUNCATE TABLE stock_transfers;
TRUNCATE TABLE stock_movements;
TRUNCATE TABLE stock_levels;

-- Catalog
TRUNCATE TABLE package_lines;
TRUNCATE TABLE service_components;
TRUNCATE TABLE offering_packs;
TRUNCATE TABLE offering_images;
TRUNCATE TABLE offerings;
TRUNCATE TABLE categories;

-- Customers: only the built-in Walk-in Customer comes back.
TRUNCATE TABLE customers;
INSERT INTO customers (code, name, is_default, note)
VALUES ('WALK-IN', 'Walk-in Customer', TRUE, 'General customer for sales to anyone not on file');

-- History of the old test data
TRUNCATE TABLE audit_logs;

-- An account made by tests, not one of the demo logins
DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE username = 'audit.test');
DELETE FROM users WHERE username = 'audit.test';

SET FOREIGN_KEY_CHECKS = 1;
