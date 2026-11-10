-- Loyalty tiers: Bronze, Silver, Gold and Platinum.
--
-- A customer on file is in the highest tier whose minimum spend they reach, counting what they
-- paid (after refunds) over the last tier_months months, or ever when tier_months is null. A tier
-- earns points faster (points_multiplier) and promotions can be kept for a tier and above. The
-- tier is kept on the customer: worked out again when they pay or return something, every night
-- (as old sales leave the window), and for everyone when the tier rules change.
CREATE TABLE loyalty_tiers (
    code              VARCHAR(10)    PRIMARY KEY,
    rank_order        INT            NOT NULL UNIQUE,
    min_spend         DECIMAL(12, 2) NOT NULL,
    points_multiplier DECIMAL(5, 2)  NOT NULL,
    CONSTRAINT ck_tier_code CHECK (code IN ('BRONZE', 'SILVER', 'GOLD', 'PLATINUM')),
    CONSTRAINT ck_tier_values CHECK (min_spend >= 0 AND points_multiplier > 0)
);

INSERT INTO loyalty_tiers (code, rank_order, min_spend, points_multiplier) VALUES
    ('BRONZE', 1, 0, 1.00),
    ('SILVER', 2, 300, 1.25),
    ('GOLD', 3, 1000, 1.50),
    ('PLATINUM', 4, 3000, 2.00);

ALTER TABLE loyalty_settings ADD COLUMN tier_months INT NULL DEFAULT 12 AFTER max_redeem_pct;

ALTER TABLE customers ADD COLUMN tier VARCHAR(10) NULL AFTER points_balance;

ALTER TABLE promotions ADD COLUMN min_tier VARCHAR(10) NULL AFTER customers_only;

-- Everyone on file starts in the tier their last 12 months of spending reaches.
UPDATE customers c
LEFT JOIN (SELECT customer_id, SUM(total - refunded_total) AS spent
           FROM orders
           WHERE status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
             AND completed_at >= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 12 MONTH)
           GROUP BY customer_id) s ON s.customer_id = c.id
SET c.tier = (SELECT t.code FROM loyalty_tiers t WHERE t.min_spend <= COALESCE(s.spent, 0) ORDER BY t.rank_order DESC LIMIT 1)
WHERE c.is_default = FALSE;
