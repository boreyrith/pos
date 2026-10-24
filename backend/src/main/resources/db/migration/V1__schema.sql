CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE refresh_tokens (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    token_hash  CHAR(64)     NOT NULL UNIQUE,
    expires_at  TIMESTAMP(6) NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE categories (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE products (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku                 VARCHAR(40)    NOT NULL UNIQUE,
    barcode             VARCHAR(64)    NULL UNIQUE,
    name                VARCHAR(150)   NOT NULL,
    category_id         BIGINT         NULL,
    price               DECIMAL(12, 2) NOT NULL,
    cost                DECIMAL(12, 2) NOT NULL DEFAULT 0,
    taxable             BOOLEAN        NOT NULL DEFAULT TRUE,
    stock               INT            NOT NULL DEFAULT 0,
    low_stock_threshold INT            NOT NULL DEFAULT 5,
    active              BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE SET NULL,
    FULLTEXT INDEX ft_product_name (name)
);

CREATE TABLE orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number    VARCHAR(30)    NULL UNIQUE,
    cashier_id      BIGINT         NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    subtotal        DECIMAL(12, 2) NOT NULL,
    discount_type   VARCHAR(10)    NULL,
    discount_value  DECIMAL(12, 2) NULL,
    discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    tax_amount      DECIMAL(12, 2) NOT NULL DEFAULT 0,
    total           DECIMAL(12, 2) NOT NULL,
    currency        CHAR(3)        NOT NULL,
    note            VARCHAR(255)   NULL,
    created_at      TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at    TIMESTAMP(6)   NULL,
    CONSTRAINT fk_order_cashier FOREIGN KEY (cashier_id) REFERENCES users (id),
    INDEX idx_order_status_created (status, created_at)
);

CREATE TABLE order_items (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    product_name VARCHAR(150)   NOT NULL,
    sku          VARCHAR(40)    NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    quantity     INT            NOT NULL,
    taxable      BOOLEAN        NOT NULL,
    line_total   DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE payments (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    method         VARCHAR(10)    NOT NULL,
    status         VARCHAR(10)    NOT NULL,
    amount         DECIMAL(12, 2) NOT NULL,
    tendered       DECIMAL(12, 2) NULL,
    change_due     DECIMAL(12, 2) NULL,
    khqr_payload   VARCHAR(512)   NULL,
    khqr_md5       CHAR(32)       NULL,
    external_ref   VARCHAR(100)   NULL,
    expires_at     TIMESTAMP(6)   NULL,
    paid_at        TIMESTAMP(6)   NULL,
    created_at     TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    INDEX idx_payment_md5 (khqr_md5)
);
