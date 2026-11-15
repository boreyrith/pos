-- The online shop (frontend-buy): customers order products online, pay by KHQR, and pick up at a branch
-- or have them delivered. An online order is an ordinary order (pricing, promotions, tax, stock,
-- payment and the books as at the till) placed by the built-in "Online shop" user, plus the
-- details below.

-- Only what staff switch on is sold online.
ALTER TABLE offerings ADD COLUMN sell_online BOOLEAN NOT NULL DEFAULT FALSE AFTER active;

-- The shop's switches. Delivery is charged as the catalog service delivery_offering_id (its price is
-- the fee), so it is taxed, receipted, booked and refunded like any line; free from free_delivery_from.
CREATE TABLE shop_settings (
    id                   TINYINT       NOT NULL PRIMARY KEY,
    enabled              BOOLEAN       NOT NULL DEFAULT FALSE,
    pickup_enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    delivery_enabled     BOOLEAN       NOT NULL DEFAULT FALSE,
    delivery_branch_id   BIGINT        NULL,
    delivery_offering_id BIGINT        NULL,
    free_delivery_from   DECIMAL(12, 2) NULL,
    updated_by           BIGINT        NULL,
    updated_at           TIMESTAMP(6)  NOT NULL,
    CONSTRAINT ck_shop_single_row CHECK (id = 1),
    CONSTRAINT ck_shop_free_from CHECK (free_delivery_from IS NULL OR free_delivery_from >= 0),
    CONSTRAINT fk_shop_delivery_branch FOREIGN KEY (delivery_branch_id) REFERENCES branches (id),
    CONSTRAINT fk_shop_delivery_offering FOREIGN KEY (delivery_offering_id) REFERENCES offerings (id),
    CONSTRAINT fk_shop_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
INSERT INTO shop_settings (id, updated_at) VALUES (1, CURRENT_TIMESTAMP(6));

-- A customer's online account: phone and password, on the customer record with that phone. Phones
-- can't be verified yet (no SMS provider), so an account sees only its own online orders and online
-- orders don't spend loyalty points.
CREATE TABLE customer_accounts (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id    BIGINT        NOT NULL,
    phone          VARCHAR(30)   NOT NULL,
    password_hash  VARCHAR(100)  NOT NULL,
    failed_logins  INT           NOT NULL DEFAULT 0,
    locked_until   TIMESTAMP(6)  NULL,
    last_login_at  TIMESTAMP(6)  NULL,
    created_at     TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uq_customer_account_customer UNIQUE (customer_id),
    CONSTRAINT uq_customer_account_phone UNIQUE (phone),
    CONSTRAINT ck_customer_account_failed CHECK (failed_logins >= 0),
    CONSTRAINT fk_customer_account_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
);

-- A signed-in shop browser: the token is kept only as a hash (it travels in an HttpOnly cookie).
CREATE TABLE customer_sessions (
    token_hash   VARCHAR(64)   NOT NULL PRIMARY KEY,
    account_id   BIGINT        NOT NULL,
    created_at   TIMESTAMP(6)  NOT NULL,
    last_used_at TIMESTAMP(6)  NOT NULL,
    expires_at   TIMESTAMP(6)  NOT NULL,
    ended_at     TIMESTAMP(6)  NULL,
    ip_address   VARCHAR(45)   NULL,
    user_agent   VARCHAR(255)  NULL,
    CONSTRAINT fk_customer_session_account FOREIGN KEY (account_id) REFERENCES customer_accounts (id),
    INDEX idx_customer_session_account (account_id, ended_at),
    INDEX idx_customer_session_expiry (expires_at)
);

-- What an online order adds to its order: how it reaches the customer, who to contact, and where it
-- is in preparation (NEW once paid, then READY to collect or OUT_FOR_DELIVERY, then DONE). A guest
-- follows the order with a private link; only the hash of its key is kept.
CREATE TABLE online_orders (
    order_id         BIGINT        NOT NULL PRIMARY KEY,
    fulfilment       VARCHAR(10)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    account_id       BIGINT        NULL,
    contact_name     VARCHAR(100)  NOT NULL,
    contact_phone    VARCHAR(30)   NOT NULL,
    address          VARCHAR(255)  NULL,
    access_key_hash  VARCHAR(64)   NOT NULL,
    created_at       TIMESTAMP(6)  NOT NULL,
    updated_at       TIMESTAMP(6)  NOT NULL,
    ready_at         TIMESTAMP(6)  NULL,
    done_at          TIMESTAMP(6)  NULL,
    updated_by       BIGINT        NULL,
    CONSTRAINT ck_online_fulfilment CHECK (fulfilment IN ('PICKUP', 'DELIVERY')),
    CONSTRAINT ck_online_status CHECK (status IN ('NEW', 'READY', 'OUT_FOR_DELIVERY', 'DONE')),
    CONSTRAINT ck_online_address CHECK (fulfilment = 'PICKUP' OR address IS NOT NULL),
    CONSTRAINT fk_online_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_online_account FOREIGN KEY (account_id) REFERENCES customer_accounts (id),
    CONSTRAINT fk_online_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    INDEX idx_online_status (status, created_at),
    INDEX idx_online_account (account_id, created_at),
    INDEX idx_online_phone (contact_phone)
);

-- Staff who prepare online orders and hand them over: cashiers, managers and admins.
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'ONLINE_ORDERS' FROM roles WHERE code IN ('ADMIN', 'MANAGER', 'CASHIER');
