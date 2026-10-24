-- Stock adjustments: documents that change stock in one storage when the physical stock differs
-- from the system's. Either free adjustments (increase / decrease / set, each with a reason) or a
-- stock count (reconcile counted quantities). Applied immediately; every line keeps before, change
-- and after, and the cost value of the change.
CREATE TABLE stock_adjustments (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjustment_number VARCHAR(30)    NULL UNIQUE,
    storage_id        BIGINT         NOT NULL,
    kind              VARCHAR(12)    NOT NULL,
    note              VARCHAR(255)   NULL,
    line_count        INT            NOT NULL DEFAULT 0,
    -- Sum of cost x change over the lines: negative for losses.
    value_change      DECIMAL(14, 2) NOT NULL DEFAULT 0,
    created_by        BIGINT         NOT NULL,
    created_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_adj_storage FOREIGN KEY (storage_id) REFERENCES storages (id),
    CONSTRAINT fk_adj_user FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_adj_created (created_at)
);

CREATE TABLE stock_adjustment_lines (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjustment_id BIGINT         NOT NULL,
    offering_id   BIGINT         NOT NULL,
    name          VARCHAR(150)   NOT NULL,
    sku           VARCHAR(40)    NOT NULL,
    unit          VARCHAR(20)    NULL,
    reason        VARCHAR(20)    NOT NULL,
    note          VARCHAR(255)   NULL,
    -- All quantities in base units.
    before_qty    INT            NOT NULL,
    change_qty    INT            NOT NULL,
    after_qty     INT            NOT NULL,
    -- Counts only: what the count sheet showed when it was loaded.
    expected_qty  INT            NULL,
    unit_cost     DECIMAL(12, 2) NOT NULL,
    value_change  DECIMAL(14, 2) NOT NULL,
    CONSTRAINT fk_adjline_adj FOREIGN KEY (adjustment_id) REFERENCES stock_adjustments (id) ON DELETE CASCADE,
    CONSTRAINT fk_adjline_offering FOREIGN KEY (offering_id) REFERENCES offerings (id)
);

ALTER TABLE stock_movements
    ADD COLUMN reason        VARCHAR(20) NULL AFTER type,
    ADD COLUMN adjustment_id BIGINT      NULL AFTER transfer_id,
    ADD CONSTRAINT fk_move_adjustment FOREIGN KEY (adjustment_id) REFERENCES stock_adjustments (id) ON DELETE SET NULL;
