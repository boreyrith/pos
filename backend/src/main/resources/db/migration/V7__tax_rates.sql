-- Named tax rates instead of one VAT rate and a taxable flag. Each catalog entry has a rate, and
-- its price can include the tax or exclude it (store default in company_settings, per-entry
-- override). Orders keep a per-rate tax breakdown so receipts stay correct when rates change.
CREATE TABLE tax_rates (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(50)   NOT NULL UNIQUE,
    -- A fraction: 0.1000 for 10%.
    rate         DECIMAL(6, 4) NOT NULL,
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order   INT           NOT NULL DEFAULT 0,
    -- Only set for the default rate, so the unique key allows exactly one.
    default_flag TINYINT AS (IF(is_default, 1, NULL)) STORED,
    CONSTRAINT uq_default_tax UNIQUE (default_flag),
    CONSTRAINT ck_tax_rate CHECK (rate >= 0 AND rate < 1)
);

INSERT INTO tax_rates (name, rate, is_default, sort_order)
VALUES ('VAT', COALESCE((SELECT vat_rate FROM company_settings WHERE id = 1), 0.10), TRUE, 0),
       ('Exempt', 0, FALSE, 1);

ALTER TABLE company_settings
    ADD COLUMN prices_include_tax BOOLEAN NOT NULL DEFAULT FALSE AFTER receipt_footer,
    DROP CONSTRAINT ck_vat_rate,
    DROP COLUMN vat_rate;

-- Catalog: a tax rate per entry, and an optional override of the store's "prices include tax".
-- The foreign keys are added after the columns are filled: MySQL can't alter a column with one.
ALTER TABLE offerings
    ADD COLUMN tax_rate_id        BIGINT  NULL AFTER taxable,
    ADD COLUMN price_includes_tax BOOLEAN NULL AFTER tax_rate_id;
UPDATE offerings SET tax_rate_id = (SELECT id FROM tax_rates WHERE name = IF(taxable, 'VAT', 'Exempt'));
ALTER TABLE offerings MODIFY tax_rate_id BIGINT NOT NULL, DROP COLUMN taxable;
ALTER TABLE offerings ADD CONSTRAINT fk_offering_tax FOREIGN KEY (tax_rate_id) REFERENCES tax_rates (id);

-- Order lines keep the tax they were sold with. `line_total` is the listed price x quantity
-- (tax included if `price_includes_tax`).
ALTER TABLE order_items
    ADD COLUMN tax_rate_id        BIGINT        NULL AFTER taxable,
    ADD COLUMN tax_name           VARCHAR(50)   NULL AFTER tax_rate_id,
    ADD COLUMN tax_rate           DECIMAL(6, 4) NULL AFTER tax_name,
    ADD COLUMN price_includes_tax BOOLEAN       NOT NULL DEFAULT FALSE AFTER tax_rate;
UPDATE order_items i JOIN tax_rates t ON t.name = IF(i.taxable, 'VAT', 'Exempt')
SET i.tax_rate_id = t.id, i.tax_name = t.name, i.tax_rate = t.rate;
ALTER TABLE order_items
    MODIFY tax_rate_id BIGINT NOT NULL,
    MODIFY tax_name VARCHAR(50) NOT NULL,
    MODIFY tax_rate DECIMAL(6, 4) NOT NULL,
    DROP COLUMN taxable;
ALTER TABLE order_items ADD CONSTRAINT fk_item_tax FOREIGN KEY (tax_rate_id) REFERENCES tax_rates (id);

-- Orders: tax_amount stays the total tax; tax_included is the part already inside the prices.
-- total = subtotal - discount + (tax_amount - tax_included).
ALTER TABLE orders ADD COLUMN tax_included DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER tax_amount;

CREATE TABLE order_taxes (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT         NOT NULL,
    tax_rate_id BIGINT         NOT NULL,
    name        VARCHAR(50)    NOT NULL,
    rate        DECIMAL(6, 4)  NOT NULL,
    included    BOOLEAN        NOT NULL,
    -- Net amount the tax is charged on, after the order discount.
    base        DECIMAL(12, 2) NOT NULL,
    tax         DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_otax_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_otax_rate FOREIGN KEY (tax_rate_id) REFERENCES tax_rates (id)
);

-- Old orders: one VAT line (prices excluded tax) and one Exempt line, rebuilt from their lines.
INSERT INTO order_taxes (order_id, tax_rate_id, name, rate, included, base, tax)
SELECT o.id, t.id, t.name, MAX(i.tax_rate), FALSE,
       ROUND(SUM(i.line_total) - o.discount_amount * SUM(i.line_total) / NULLIF(o.subtotal, 0), 2),
       IF(t.name = 'VAT', o.tax_amount, 0)
FROM orders o JOIN order_items i ON i.order_id = o.id JOIN tax_rates t ON t.id = i.tax_rate_id
GROUP BY o.id, t.id, t.name, o.discount_amount, o.subtotal, o.tax_amount;
