-- Stock moves from one number per catalog entry to a quantity per storage. Each branch has
-- storages, one of which is the selling storage that sales take stock from. Existing data moves
-- into a "Main branch" with a single "Shop floor" storage.
CREATE TABLE branches (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    address    VARCHAR(255) NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE storages (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id         BIGINT      NOT NULL,
    name              VARCHAR(80) NOT NULL,
    selling           BOOLEAN     NOT NULL DEFAULT FALSE,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Only set for the selling storage, so the unique key allows one selling storage per branch.
    selling_branch_id BIGINT AS (IF(selling, branch_id, NULL)) STORED,
    CONSTRAINT fk_storage_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT uq_storage_name UNIQUE (branch_id, name),
    CONSTRAINT uq_selling_storage UNIQUE (selling_branch_id)
);

INSERT INTO branches (code, name) VALUES ('MAIN', 'Main branch');
INSERT INTO storages (branch_id, name, selling) SELECT id, 'Shop floor', TRUE FROM branches WHERE code = 'MAIN';

CREATE TABLE stock_levels (
    storage_id  BIGINT NOT NULL,
    offering_id BIGINT NOT NULL,
    quantity    INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (storage_id, offering_id),
    INDEX idx_stock_offering (offering_id),
    CONSTRAINT fk_level_storage FOREIGN KEY (storage_id) REFERENCES storages (id),
    CONSTRAINT fk_level_offering FOREIGN KEY (offering_id) REFERENCES offerings (id) ON DELETE CASCADE,
    CONSTRAINT ck_level_quantity CHECK (quantity >= 0)
);

INSERT INTO stock_levels (storage_id, offering_id, quantity)
SELECT s.id, o.id, o.stock FROM offerings o CROSS JOIN storages s WHERE o.type <> 'SERVICE';

-- Staff belong to a branch.
ALTER TABLE users ADD COLUMN branch_id BIGINT NULL, ADD CONSTRAINT fk_user_branch FOREIGN KEY (branch_id) REFERENCES branches (id);
UPDATE users SET branch_id = (SELECT id FROM branches WHERE code = 'MAIN');
ALTER TABLE users MODIFY branch_id BIGINT NOT NULL;

-- Orders record where they were sold and which storage the stock came from.
ALTER TABLE orders
    ADD COLUMN branch_id  BIGINT NULL AFTER cashier_id,
    ADD COLUMN storage_id BIGINT NULL AFTER branch_id,
    ADD CONSTRAINT fk_order_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    ADD CONSTRAINT fk_order_storage FOREIGN KEY (storage_id) REFERENCES storages (id);
UPDATE orders SET branch_id = (SELECT id FROM branches WHERE code = 'MAIN'),
                  storage_id = (SELECT s.id FROM storages s JOIN branches b ON b.id = s.branch_id WHERE b.code = 'MAIN');
ALTER TABLE orders
    MODIFY branch_id BIGINT NOT NULL,
    MODIFY storage_id BIGINT NOT NULL,
    ADD INDEX idx_order_branch (branch_id, status, created_at);

-- Transfers between storages. Within a branch they complete at once; between branches the stock
-- is in transit until the receiving branch confirms.
CREATE TABLE stock_transfers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_number VARCHAR(30)  NULL UNIQUE,
    from_storage_id BIGINT       NOT NULL,
    to_storage_id   BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    note            VARCHAR(255) NULL,
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_by       BIGINT       NULL,
    closed_at       TIMESTAMP(6) NULL,
    CONSTRAINT fk_transfer_from FOREIGN KEY (from_storage_id) REFERENCES storages (id),
    CONSTRAINT fk_transfer_to FOREIGN KEY (to_storage_id) REFERENCES storages (id),
    CONSTRAINT fk_transfer_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_transfer_closer FOREIGN KEY (closed_by) REFERENCES users (id),
    INDEX idx_transfer_status (status, created_at)
);

CREATE TABLE stock_transfer_lines (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id BIGINT       NOT NULL,
    offering_id BIGINT       NOT NULL,
    name        VARCHAR(150) NOT NULL,
    sku         VARCHAR(40)  NOT NULL,
    unit        VARCHAR(20)  NULL,
    -- In base units.
    quantity    INT          NOT NULL,
    CONSTRAINT fk_tline_transfer FOREIGN KEY (transfer_id) REFERENCES stock_transfers (id) ON DELETE CASCADE,
    CONSTRAINT fk_tline_offering FOREIGN KEY (offering_id) REFERENCES offerings (id),
    CONSTRAINT ck_tline_quantity CHECK (quantity > 0)
);

-- Every stock change. `quantity` is signed (negative takes stock away); `balance` is the
-- storage's quantity after the change.
CREATE TABLE stock_movements (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    offering_id BIGINT       NOT NULL,
    storage_id  BIGINT       NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    quantity    INT          NOT NULL,
    balance     INT          NOT NULL,
    user_id     BIGINT       NULL,
    order_id    BIGINT       NULL,
    transfer_id BIGINT       NULL,
    note        VARCHAR(255) NULL,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_move_offering FOREIGN KEY (offering_id) REFERENCES offerings (id) ON DELETE CASCADE,
    CONSTRAINT fk_move_storage FOREIGN KEY (storage_id) REFERENCES storages (id),
    CONSTRAINT fk_move_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_move_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE SET NULL,
    CONSTRAINT fk_move_transfer FOREIGN KEY (transfer_id) REFERENCES stock_transfers (id) ON DELETE SET NULL,
    INDEX idx_move_offering (offering_id, created_at),
    INDEX idx_move_storage (storage_id, created_at)
);

INSERT INTO stock_movements (offering_id, storage_id, type, quantity, balance, note)
SELECT o.id, s.id, 'OPENING', o.stock, o.stock, 'Stock when branches were introduced'
FROM offerings o CROSS JOIN storages s WHERE o.type <> 'SERVICE';

ALTER TABLE offerings DROP COLUMN stock;
