-- Products, services and items share one catalog table, told apart by `type`.
-- Existing rows become PRODUCTs, so nothing changes for data created before this migration.
RENAME TABLE products TO offerings;

ALTER TABLE offerings
    ADD COLUMN type             VARCHAR(10) NOT NULL DEFAULT 'PRODUCT' AFTER id,
    ADD COLUMN unit             VARCHAR(20) NULL AFTER name,
    -- Items are only offered at checkout when sellable; products and services always are.
    ADD COLUMN sellable         BOOLEAN     NOT NULL DEFAULT TRUE AFTER active,
    -- Services only: FIXED (price per job) or HOURLY (price per hour).
    ADD COLUMN pricing          VARCHAR(10) NULL AFTER price,
    ADD COLUMN duration_minutes INT         NULL AFTER pricing,
    ADD INDEX idx_offering_type (type, active);

ALTER TABLE offerings ALTER COLUMN type DROP DEFAULT;

-- The parts a service uses up each time it is sold, e.g. a screen repair uses 1 screen panel.
CREATE TABLE service_components (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_id BIGINT NOT NULL,
    item_id    BIGINT NOT NULL,
    quantity   INT    NOT NULL,
    CONSTRAINT fk_component_service FOREIGN KEY (service_id) REFERENCES offerings (id) ON DELETE CASCADE,
    CONSTRAINT fk_component_item FOREIGN KEY (item_id) REFERENCES offerings (id),
    CONSTRAINT uq_component UNIQUE (service_id, item_id)
);

ALTER TABLE order_items
    RENAME COLUMN product_id TO offering_id,
    RENAME COLUMN product_name TO name;

ALTER TABLE order_items
    ADD COLUMN offering_type    VARCHAR(10)   NOT NULL DEFAULT 'PRODUCT' AFTER offering_id,
    ADD COLUMN unit             VARCHAR(20)   NULL AFTER sku,
    -- Hourly services: hours worked. The line total is unit price x quantity x hours.
    ADD COLUMN hours            DECIMAL(6, 2) NULL AFTER quantity,
    ADD COLUMN duration_minutes INT           NULL AFTER hours,
    ADD COLUMN staff_id         BIGINT        NULL,
    ADD COLUMN staff_name       VARCHAR(100)  NULL,
    ADD CONSTRAINT fk_item_staff FOREIGN KEY (staff_id) REFERENCES users (id);

ALTER TABLE order_items ALTER COLUMN offering_type DROP DEFAULT;

-- The parts a sold service actually used, so a refund or cancellation returns exactly these
-- even if the service's parts list changes later.
CREATE TABLE order_item_components (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_item_id BIGINT       NOT NULL,
    item_id       BIGINT       NOT NULL,
    item_name     VARCHAR(150) NOT NULL,
    quantity      INT          NOT NULL,
    CONSTRAINT fk_used_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_used_item FOREIGN KEY (item_id) REFERENCES offerings (id)
);
