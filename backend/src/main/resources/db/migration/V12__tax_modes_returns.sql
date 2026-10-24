-- Tax modes and partial returns.
--  * company_settings: one tax mode (NONE / INCLUSIVE / EXCLUSIVE) replaces "prices include tax" and
--    the per-entry overrides; plus display name, maximum rate and which entry types are taxed.
--  * orders / order_items: a full tax snapshot per order and a per-line split (discount, net, tax),
--    so partial returns refund exactly what each line was charged.
--  * order_returns: refunds, full or partial, with their own tax amounts.
-- Existing orders keep their totals; the per-line split is derived from their saved tax breakdown.

ALTER TABLE company_settings
    ADD COLUMN tax_mode     VARCHAR(12)   NOT NULL DEFAULT 'EXCLUSIVE' AFTER receipt_footer,
    ADD COLUMN tax_label    VARCHAR(30)   NOT NULL DEFAULT 'VAT' AFTER tax_mode,
    ADD COLUMN tax_max_rate DECIMAL(6, 4) NOT NULL DEFAULT 0.3000 AFTER tax_label,
    ADD COLUMN tax_products BOOLEAN       NOT NULL DEFAULT TRUE AFTER tax_max_rate,
    ADD COLUMN tax_services BOOLEAN       NOT NULL DEFAULT TRUE AFTER tax_products,
    ADD COLUMN tax_items    BOOLEAN       NOT NULL DEFAULT TRUE AFTER tax_services,
    ADD COLUMN tax_packages BOOLEAN       NOT NULL DEFAULT TRUE AFTER tax_items;
UPDATE company_settings SET tax_mode = IF(prices_include_tax, 'INCLUSIVE', 'EXCLUSIVE');
ALTER TABLE company_settings
    DROP COLUMN prices_include_tax,
    ADD CONSTRAINT ck_tax_mode CHECK (tax_mode IN ('NONE', 'INCLUSIVE', 'EXCLUSIVE')),
    ADD CONSTRAINT ck_tax_max_rate CHECK (tax_max_rate > 0 AND tax_max_rate < 1);

-- The mode now decides whether prices include tax, for every entry.
ALTER TABLE offerings DROP COLUMN price_includes_tax;

ALTER TABLE orders
    ADD COLUMN tax_mode           VARCHAR(12)    NULL AFTER tax_included,
    ADD COLUMN tax_label          VARCHAR(30)    NULL AFTER tax_mode,
    -- Net amounts (after discount, before tax) of taxable and non-taxable lines.
    ADD COLUMN taxable_amount     DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER tax_label,
    ADD COLUMN non_taxable_amount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER taxable_amount,
    ADD COLUMN refunded_total     DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER total,
    ADD COLUMN refunded_tax       DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER refunded_total;

ALTER TABLE order_items
    ADD COLUMN line_discount   DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER line_total,
    ADD COLUMN line_net        DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER line_discount,
    ADD COLUMN line_tax        DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER line_net,
    -- What the customer paid for the line: line_total - line_discount (+ line_tax if added on top).
    ADD COLUMN line_paid       DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER line_tax,
    ADD COLUMN refunded_qty    INT            NOT NULL DEFAULT 0 AFTER line_paid,
    ADD COLUMN refunded_amount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER refunded_qty,
    ADD COLUMN refunded_tax    DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER refunded_amount;

-- Existing orders: share each order's discount and each tax group's tax over its lines by value.
UPDATE order_items i
JOIN (
    SELECT x.id,
           IF(o.subtotal > 0, ROUND(o.discount_amount * x.line_total / o.subtotal, 2), 0) AS disc,
           IF(g.gross > 0, ROUND(COALESCE(t.tax, 0) * x.line_total / g.gross, 2), 0) AS tax
    FROM order_items x
    JOIN orders o ON o.id = x.order_id
    JOIN (SELECT order_id, tax_rate_id, price_includes_tax, SUM(line_total) AS gross
          FROM order_items GROUP BY order_id, tax_rate_id, price_includes_tax) g
         ON g.order_id = x.order_id AND g.tax_rate_id = x.tax_rate_id AND g.price_includes_tax = x.price_includes_tax
    LEFT JOIN order_taxes t
         ON t.order_id = x.order_id AND t.tax_rate_id = x.tax_rate_id AND t.included = x.price_includes_tax
) s ON s.id = i.id
SET i.line_discount = s.disc,
    i.line_tax      = s.tax,
    i.line_net      = i.line_total - s.disc - IF(i.price_includes_tax, s.tax, 0),
    i.line_paid     = i.line_total - s.disc + IF(i.price_includes_tax, 0, s.tax);

-- Rounding: the last line of each order / tax group takes the leftover cent so lines add up exactly.
UPDATE order_items i
JOIN (
    SELECT o.id AS order_id, o.discount_amount - SUM(x.line_discount) AS diff, MAX(x.id) AS last_id
    FROM orders o JOIN order_items x ON x.order_id = o.id GROUP BY o.id, o.discount_amount
) d ON d.last_id = i.id
SET i.line_discount = i.line_discount + d.diff
WHERE d.diff <> 0;
UPDATE order_items i
JOIN (
    SELECT x.order_id, x.tax_rate_id, x.price_includes_tax, COALESCE(MAX(t.tax), 0) - SUM(x.line_tax) AS diff, MAX(x.id) AS last_id
    FROM order_items x
    LEFT JOIN order_taxes t ON t.order_id = x.order_id AND t.tax_rate_id = x.tax_rate_id AND t.included = x.price_includes_tax
    GROUP BY x.order_id, x.tax_rate_id, x.price_includes_tax
) d ON d.last_id = i.id
SET i.line_tax = i.line_tax + d.diff
WHERE d.diff <> 0;
UPDATE order_items i
SET i.line_net  = i.line_total - i.line_discount - IF(i.price_includes_tax, i.line_tax, 0),
    i.line_paid = i.line_total - i.line_discount + IF(i.price_includes_tax, 0, i.line_tax);

UPDATE orders o
SET o.tax_mode = IF(EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND i.price_includes_tax), 'INCLUSIVE', 'EXCLUSIVE'),
    o.tax_label = 'VAT',
    o.taxable_amount = COALESCE((SELECT SUM(t.base) FROM order_taxes t WHERE t.order_id = o.id AND t.rate > 0), 0),
    o.non_taxable_amount = COALESCE((SELECT SUM(t.base) FROM order_taxes t WHERE t.order_id = o.id AND t.rate = 0), 0);
ALTER TABLE orders MODIFY tax_mode VARCHAR(12) NOT NULL, MODIFY tax_label VARCHAR(30) NOT NULL;

CREATE TABLE order_returns (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_number VARCHAR(30)    NULL UNIQUE,
    order_id      BIGINT         NOT NULL,
    reason        VARCHAR(255)   NULL,
    -- Refunded to the customer, the tax part of it, and the rest.
    total         DECIMAL(12, 2) NOT NULL,
    tax           DECIMAL(12, 2) NOT NULL,
    net           DECIMAL(12, 2) NOT NULL,
    created_by    BIGINT         NOT NULL,
    created_at    TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_return_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_user FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_return_created (created_at)
);

CREATE TABLE order_return_lines (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_id     BIGINT         NOT NULL,
    order_item_id BIGINT         NOT NULL,
    quantity      INT            NOT NULL,
    total         DECIMAL(12, 2) NOT NULL,
    tax           DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_rline_return FOREIGN KEY (return_id) REFERENCES order_returns (id) ON DELETE CASCADE,
    CONSTRAINT fk_rline_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT ck_rline_quantity CHECK (quantity > 0)
);

-- Orders refunded before returns existed: one full return each. The refund date wasn't recorded,
-- so it is set to when the order was completed, and the cashier stands in for who refunded it.
INSERT INTO order_returns (order_id, reason, total, tax, net, created_by, created_at)
SELECT id, 'Full refund (recorded before returns were tracked)', total, tax_amount, total - tax_amount, cashier_id,
       COALESCE(completed_at, created_at)
FROM orders WHERE status = 'REFUNDED';
UPDATE order_returns SET return_number = CONCAT('RET-', LPAD(id, 5, '0')) WHERE return_number IS NULL;
INSERT INTO order_return_lines (return_id, order_item_id, quantity, total, tax)
SELECT r.id, i.id, i.quantity, i.line_paid, i.line_tax
FROM order_returns r JOIN order_items i ON i.order_id = r.order_id;
UPDATE order_items i JOIN orders o ON o.id = i.order_id
SET i.refunded_qty = i.quantity, i.refunded_amount = i.line_paid, i.refunded_tax = i.line_tax
WHERE o.status = 'REFUNDED';
UPDATE orders SET refunded_total = total, refunded_tax = tax_amount WHERE status = 'REFUNDED';
