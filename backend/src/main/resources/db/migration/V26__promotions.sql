-- Marketing, part 1: promotions and coupon codes.
--
-- A promotion takes money off a sale automatically when its conditions hold (dates, days, hours,
-- branches, customers, minimum spend, usage limits), or only when one of its coupon codes is
-- entered. Kinds:
--   ITEM_DISCOUNT  % or amount off each qualifying line (an amount is per unit)
--   BUY_X_GET_Y    for every buy_qty + get_qty qualifying units, the cheapest get_qty are discount_value % off
--   ORDER_DISCOUNT % or amount off the whole order (a % can be capped by max_discount)
-- What a promotion took is kept on the order (order_promotions, and per line on order_items), so
-- receipts, refunds and reports never depend on the promotion as it is later.
CREATE TABLE promotions (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(120)   NOT NULL,
    description        VARCHAR(500)   NULL,
    kind               VARCHAR(20)    NOT NULL,
    discount_type      VARCHAR(10)    NOT NULL,
    discount_value     DECIMAL(12, 2) NOT NULL,
    -- What qualifies: everything, some categories, or some catalog entries (promotion_targets).
    scope              VARCHAR(12)    NOT NULL DEFAULT 'ALL',
    buy_qty            INT            NULL,
    get_qty            INT            NULL,
    -- The order (after line promotions and item discounts) must come to at least this.
    min_spend          DECIMAL(12, 2) NULL,
    max_discount       DECIMAL(12, 2) NULL,
    -- In the store's time zone; null means no limit. Days: ISO numbers, Monday = 1, e.g. "6,7".
    starts_on          DATE           NULL,
    ends_on            DATE           NULL,
    days_of_week       VARCHAR(20)    NULL,
    time_from          TIME           NULL,
    time_to            TIME           NULL,
    requires_code      BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Only for customers on file (not the Walk-in Customer).
    customers_only     BOOLEAN        NOT NULL DEFAULT FALSE,
    usage_limit        INT            NULL,
    per_customer_limit INT            NULL,
    -- Sales using it, not counting cancelled or expired ones.
    used_count         INT            NOT NULL DEFAULT 0,
    -- Higher goes first when two promotions want the same line.
    priority           INT            NOT NULL DEFAULT 0,
    active             BOOLEAN        NOT NULL DEFAULT TRUE,
    created_by         BIGINT         NOT NULL,
    created_at         TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_promotion_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_promotion_kind CHECK (kind IN ('ITEM_DISCOUNT', 'BUY_X_GET_Y', 'ORDER_DISCOUNT')),
    CONSTRAINT ck_promotion_discount_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT ck_promotion_scope CHECK (scope IN ('ALL', 'CATEGORIES', 'OFFERINGS')),
    CONSTRAINT ck_promotion_value CHECK (discount_value > 0),
    CONSTRAINT ck_promotion_bxgy CHECK (kind <> 'BUY_X_GET_Y' OR (buy_qty >= 1 AND get_qty >= 1)),
    INDEX idx_promotion_active (active, requires_code)
);

-- The branches a promotion runs in; none means every branch.
CREATE TABLE promotion_branches (
    promotion_id BIGINT NOT NULL,
    branch_id    BIGINT NOT NULL,
    PRIMARY KEY (promotion_id, branch_id),
    CONSTRAINT fk_promo_branch_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    CONSTRAINT fk_promo_branch_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
);

-- What qualifies when the scope is CATEGORIES or OFFERINGS.
CREATE TABLE promotion_categories (
    promotion_id BIGINT NOT NULL,
    category_id  BIGINT NOT NULL,
    PRIMARY KEY (promotion_id, category_id),
    CONSTRAINT fk_promo_cat_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    CONSTRAINT fk_promo_cat_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE promotion_offerings (
    promotion_id BIGINT NOT NULL,
    offering_id  BIGINT NOT NULL,
    PRIMARY KEY (promotion_id, offering_id),
    CONSTRAINT fk_promo_off_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    CONSTRAINT fk_promo_off_offering FOREIGN KEY (offering_id) REFERENCES offerings (id)
);

-- Codes for promotions that need one. max_uses null = any number of times (a shared code like
-- SUMMER10); 1 = single use. A code can be issued to one customer (campaigns), who alone can use it.
CREATE TABLE coupon_codes (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    promotion_id BIGINT       NOT NULL,
    code         VARCHAR(40)  NOT NULL UNIQUE,
    max_uses     INT          NULL,
    used_count   INT          NOT NULL DEFAULT 0,
    customer_id  BIGINT       NULL,
    expires_on   DATE         NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by   BIGINT       NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_coupon_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_coupon_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_coupon_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_coupon_max_uses CHECK (max_uses IS NULL OR max_uses >= 1),
    INDEX idx_coupon_promotion (promotion_id)
);

-- What each promotion took off a sale, as it was then.
CREATE TABLE order_promotions (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    promotion_id   BIGINT         NOT NULL,
    coupon_code_id BIGINT         NULL,
    code           VARCHAR(40)    NULL,
    name           VARCHAR(120)   NOT NULL,
    kind           VARCHAR(20)    NOT NULL,
    amount         DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_order_promo_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_promo_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_order_promo_coupon FOREIGN KEY (coupon_code_id) REFERENCES coupon_codes (id),
    INDEX idx_order_promo_promotion (promotion_id),
    INDEX idx_order_promo_order (order_id)
);

ALTER TABLE order_items
    -- What a promotion took off this line (part of line_discount), and which.
    ADD COLUMN promo_discount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER item_discount,
    ADD COLUMN promotion_name VARCHAR(120)   NULL AFTER promo_discount;

ALTER TABLE orders
    -- Promotions on all lines, the order promotion (coupon or automatic), and the coupon entered.
    ADD COLUMN promo_discount       DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER item_discount,
    ADD COLUMN order_promo_discount DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER promo_discount,
    ADD COLUMN coupon_code          VARCHAR(40)    NULL AFTER order_promo_discount;

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r JOIN (
    SELECT 'ADMIN' AS code, 'MARKETING_VIEW' AS permission UNION ALL SELECT 'ADMIN', 'PROMOTIONS_MANAGE'
    UNION ALL SELECT 'MANAGER', 'MARKETING_VIEW' UNION ALL SELECT 'MANAGER', 'PROMOTIONS_MANAGE'
    UNION ALL SELECT 'ACCOUNTANT', 'MARKETING_VIEW'
) p ON p.code = r.code;
