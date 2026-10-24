-- Prices, totals and reports stay in the base currency (USD). Customers can pay in any active
-- currency at the current rate, which managers set; every rate change is kept.
CREATE TABLE currencies (
    code          CHAR(3)        NOT NULL PRIMARY KEY,
    name          VARCHAR(50)    NOT NULL,
    symbol        VARCHAR(8)     NOT NULL,
    decimals      INT            NOT NULL,
    -- Smallest amount handed over in cash, e.g. 100 for riel, 0.01 for dollars.
    cash_rounding DECIMAL(12, 4) NOT NULL,
    base          BOOLEAN        NOT NULL DEFAULT FALSE,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order    INT            NOT NULL DEFAULT 0,
    -- Only set for the base currency, so the unique key allows exactly one.
    base_flag     TINYINT AS (IF(base, 1, NULL)) STORED,
    CONSTRAINT uq_base_currency UNIQUE (base_flag),
    CONSTRAINT ck_currency_decimals CHECK (decimals BETWEEN 0 AND 4),
    CONSTRAINT ck_currency_rounding CHECK (cash_rounding > 0)
);

INSERT INTO currencies (code, name, symbol, decimals, cash_rounding, base, active, sort_order) VALUES
    ('USD', 'US dollar', '$', 2, 0.01, TRUE, TRUE, 0),
    ('KHR', 'Cambodian riel', '៛', 0, 100, FALSE, TRUE, 1),
    ('THB', 'Thai baht', '฿', 2, 0.25, FALSE, FALSE, 2);

-- Units of the currency per 1 base unit. The newest row is the current rate.
CREATE TABLE exchange_rates (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    currency_code CHAR(3)        NOT NULL,
    rate          DECIMAL(18, 6) NOT NULL,
    note          VARCHAR(255)   NULL,
    created_by    BIGINT         NULL,
    created_at    TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_rate_currency FOREIGN KEY (currency_code) REFERENCES currencies (code),
    CONSTRAINT fk_rate_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_rate_positive CHECK (rate > 0),
    INDEX idx_rate_current (currency_code, created_at)
);

INSERT INTO exchange_rates (currency_code, rate, note) VALUES
    ('KHR', 4100, 'Starting rate'),
    ('THB', 35, 'Starting rate');

-- The rates an order was sold at, for receipts and for checking payments later.
CREATE TABLE order_rates (
    order_id      BIGINT         NOT NULL,
    currency_code CHAR(3)        NOT NULL,
    rate          DECIMAL(18, 6) NOT NULL,
    PRIMARY KEY (order_id, currency_code),
    CONSTRAINT fk_order_rate_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_rate_currency FOREIGN KEY (currency_code) REFERENCES currencies (code)
);

-- Payments: `amount`, `tendered` and `change_due` stay in the base currency.
-- KHQR: `currency` / `currency_amount` is what the QR code charges (USD or KHR).
-- Cash: change is handed over in `change_currency` as `change_amount`.
ALTER TABLE payments
    ADD COLUMN currency        CHAR(3)        NULL AFTER amount,
    ADD COLUMN currency_amount DECIMAL(18, 4) NULL AFTER currency,
    ADD COLUMN change_currency CHAR(3)        NULL AFTER change_due,
    ADD COLUMN change_amount   DECIMAL(18, 4) NULL AFTER change_currency,
    ADD CONSTRAINT fk_payment_currency FOREIGN KEY (currency) REFERENCES currencies (code),
    ADD CONSTRAINT fk_payment_change_currency FOREIGN KEY (change_currency) REFERENCES currencies (code);

UPDATE payments SET currency = 'USD', currency_amount = amount;
UPDATE payments SET change_currency = 'USD', change_amount = change_due WHERE method = 'CASH';

-- Cash handed over, per currency.
CREATE TABLE payment_tenders (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id    BIGINT         NOT NULL,
    currency_code CHAR(3)        NOT NULL,
    amount        DECIMAL(18, 4) NOT NULL,
    rate          DECIMAL(18, 6) NOT NULL,
    -- `amount` converted to the base currency.
    base_amount   DECIMAL(12, 4) NOT NULL,
    CONSTRAINT fk_tender_payment FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE CASCADE,
    CONSTRAINT fk_tender_currency FOREIGN KEY (currency_code) REFERENCES currencies (code),
    CONSTRAINT ck_tender_amount CHECK (amount > 0)
);

INSERT INTO payment_tenders (payment_id, currency_code, amount, rate, base_amount)
SELECT id, 'USD', tendered, 1, tendered FROM payments WHERE method = 'CASH' AND tendered > 0;
