-- Marketing, part 2: loyalty points.
--
-- Customers on file earn points on what they pay and redeem them as a discount at checkout (the last
-- discount, before tax). The store sets the rules once (loyalty_settings, a single row). Every change
-- to a balance is a row in loyalty_transactions, so a balance can always be explained; customers
-- keep the running balance. A refund takes back the points earned on what is refunded and gives back
-- the points used on it, in proportion; a sale cancelled before payment gives back the points used.
CREATE TABLE loyalty_settings (
    id                 BIGINT PRIMARY KEY,
    enabled            BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Points for each 1.00 of the base currency paid (after all discounts, tax included).
    points_per_unit    DECIMAL(10, 4) NOT NULL DEFAULT 1,
    -- What one point is worth when redeemed, in the base currency.
    point_value        DECIMAL(10, 4) NOT NULL DEFAULT 0.01,
    -- The fewest points that can be used at once.
    min_redeem         INT            NOT NULL DEFAULT 100,
    -- The most of a sale (after discounts, before tax) points can pay, in %.
    max_redeem_pct     DECIMAL(5, 2)  NOT NULL DEFAULT 50,
    updated_by         BIGINT         NULL,
    updated_at         TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_loyalty_settings_user FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_loyalty_rates CHECK (points_per_unit >= 0 AND point_value > 0 AND min_redeem >= 1
        AND max_redeem_pct > 0 AND max_redeem_pct <= 100)
);

INSERT INTO loyalty_settings (id) VALUES (1);

ALTER TABLE customers ADD COLUMN points_balance INT NOT NULL DEFAULT 0 AFTER note;

-- EARN on a paid sale; REDEEM at checkout; RESTORE when used points come back (cancelled sale or a
-- refund); REVERSE when earned points are taken back (refund); ADJUST by staff, with a note.
CREATE TABLE loyalty_transactions (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id   BIGINT       NOT NULL,
    type          VARCHAR(10)  NOT NULL,
    points        INT          NOT NULL,
    balance_after INT          NOT NULL,
    order_id      BIGINT       NULL,
    return_id     BIGINT       NULL,
    note          VARCHAR(255) NULL,
    created_by    BIGINT       NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_loyalty_tx_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_loyalty_tx_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_loyalty_tx_return FOREIGN KEY (return_id) REFERENCES order_returns (id),
    CONSTRAINT fk_loyalty_tx_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_loyalty_tx_type CHECK (type IN ('EARN', 'REDEEM', 'RESTORE', 'REVERSE', 'ADJUST')),
    INDEX idx_loyalty_tx_customer (customer_id, created_at),
    INDEX idx_loyalty_tx_created (created_at)
);

ALTER TABLE orders
    ADD COLUMN points_redeemed INT            NOT NULL DEFAULT 0 AFTER coupon_code,
    ADD COLUMN points_discount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER points_redeemed,
    ADD COLUMN points_earned   INT            NOT NULL DEFAULT 0 AFTER points_discount;

ALTER TABLE order_returns
    -- Earned points taken back, and used points given back, with this return.
    ADD COLUMN points_reversed INT NOT NULL DEFAULT 0,
    ADD COLUMN points_restored INT NOT NULL DEFAULT 0;

INSERT INTO role_permissions (role_id, permission)
SELECT id, 'LOYALTY_MANAGE' FROM roles WHERE code IN ('ADMIN', 'MANAGER');
