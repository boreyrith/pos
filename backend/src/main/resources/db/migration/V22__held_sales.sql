-- Held (parked) sales, kept on the server so any till in the branch can finish them, nothing is lost
-- with a browser tab, and every hold, resume, void and expiry is audited. A hold being worked on is
-- locked to that cashier (IN_USE); abandoned locks are released and old holds expire automatically.
CREATE TABLE held_sales (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_number     VARCHAR(30)    NULL UNIQUE,
    branch_id       BIGINT         NOT NULL,
    customer_id     BIGINT         NULL,
    label           VARCHAR(100)   NULL,
    note            VARCHAR(255)   NULL,
    status          VARCHAR(12)    NOT NULL,
    -- The cart: lines (entry, quantity, hours, staff, price change, item discount) and order discount, as JSON.
    payload         TEXT           NOT NULL,
    item_count      INT            NOT NULL,
    estimated_total DECIMAL(12, 2) NOT NULL,
    created_by      BIGINT         NOT NULL,
    created_at      TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by      BIGINT         NULL,
    updated_at      TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_by       BIGINT         NULL,
    locked_at       TIMESTAMP(6)   NULL,
    order_id        BIGINT         NULL,
    closed_by       BIGINT         NULL,
    closed_at       TIMESTAMP(6)   NULL,
    void_reason     VARCHAR(255)   NULL,
    CONSTRAINT fk_hold_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT fk_hold_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_hold_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_hold_updater FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT fk_hold_locker FOREIGN KEY (locked_by) REFERENCES users (id),
    CONSTRAINT fk_hold_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_hold_closer FOREIGN KEY (closed_by) REFERENCES users (id),
    CONSTRAINT ck_hold_status CHECK (status IN ('HELD', 'IN_USE', 'COMPLETED', 'VOIDED', 'EXPIRED')),
    INDEX idx_hold_branch_status (branch_id, status, updated_at)
);

INSERT INTO role_permissions (role_id, permission)
SELECT id, 'HOLDS_MANAGE' FROM roles WHERE code IN ('ADMIN', 'MANAGER');
