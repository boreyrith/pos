-- Purchasing: vendors, purchase orders, receiving (goods receipts that add stock), vendor
-- invoices and payments. Amounts are in the base currency. Quantities on order lines are in the
-- line's unit (base unit or a pack); receipts convert them to base units for stock.
CREATE TABLE vendors (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL UNIQUE,
    name               VARCHAR(150) NOT NULL,
    contact_name       VARCHAR(100) NULL,
    phone              VARCHAR(50)  NULL,
    email              VARCHAR(100) NULL,
    address            VARCHAR(255) NULL,
    tax_id             VARCHAR(50)  NULL,
    -- Days to pay an invoice; sets its default due date.
    payment_terms_days INT          NOT NULL DEFAULT 0,
    note               VARCHAR(500) NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_vendor_terms CHECK (payment_terms_days BETWEEN 0 AND 365)
);

CREATE TABLE purchase_orders (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_number     VARCHAR(30)    NULL UNIQUE,
    vendor_id     BIGINT         NOT NULL,
    branch_id     BIGINT         NOT NULL,
    -- Where deliveries go.
    storage_id    BIGINT         NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    expected_date DATE           NULL,
    vendor_ref    VARCHAR(60)    NULL,
    note          VARCHAR(500)   NULL,
    -- Sum of line totals (cost excluding any invoice tax).
    total         DECIMAL(12, 2) NOT NULL DEFAULT 0,
    created_by    BIGINT         NOT NULL,
    created_at    TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ordered_at    TIMESTAMP(6)   NULL,
    closed_at     TIMESTAMP(6)   NULL,
    CONSTRAINT fk_po_vendor FOREIGN KEY (vendor_id) REFERENCES vendors (id),
    CONSTRAINT fk_po_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT fk_po_storage FOREIGN KEY (storage_id) REFERENCES storages (id),
    CONSTRAINT fk_po_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_po_status CHECK (status IN ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    INDEX idx_po_status (branch_id, status, created_at)
);

CREATE TABLE purchase_order_lines (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_order_id BIGINT         NOT NULL,
    offering_id       BIGINT         NOT NULL,
    name              VARCHAR(150)   NOT NULL,
    sku               VARCHAR(40)    NOT NULL,
    unit_id           BIGINT         NULL,
    unit              VARCHAR(20)    NULL,
    -- Base units in one of `unit` (1 for the base unit).
    factor            INT            NOT NULL,
    quantity          INT            NOT NULL,
    received_qty      INT            NOT NULL DEFAULT 0,
    -- Per `unit`.
    unit_cost         DECIMAL(12, 4) NOT NULL,
    line_total        DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_pol_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_pol_offering FOREIGN KEY (offering_id) REFERENCES offerings (id),
    CONSTRAINT ck_pol_quantity CHECK (quantity > 0 AND received_qty >= 0 AND received_qty <= quantity),
    CONSTRAINT ck_pol_factor CHECK (factor > 0),
    CONSTRAINT ck_pol_cost CHECK (unit_cost >= 0)
);

CREATE TABLE goods_receipts (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_number    VARCHAR(30)  NULL UNIQUE,
    purchase_order_id BIGINT       NOT NULL,
    storage_id        BIGINT       NOT NULL,
    delivery_ref      VARCHAR(60)  NULL,
    note              VARCHAR(255) NULL,
    received_by       BIGINT       NOT NULL,
    received_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_grn_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_grn_storage FOREIGN KEY (storage_id) REFERENCES storages (id),
    CONSTRAINT fk_grn_user FOREIGN KEY (received_by) REFERENCES users (id)
);

CREATE TABLE goods_receipt_lines (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_receipt_id BIGINT         NOT NULL,
    po_line_id       BIGINT         NOT NULL,
    -- In the order line's unit, and in base units as stocked.
    quantity         INT            NOT NULL,
    base_quantity    INT            NOT NULL,
    unit_cost        DECIMAL(12, 4) NOT NULL,
    CONSTRAINT fk_grl_grn FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts (id) ON DELETE CASCADE,
    CONSTRAINT fk_grl_line FOREIGN KEY (po_line_id) REFERENCES purchase_order_lines (id),
    CONSTRAINT ck_grl_quantity CHECK (quantity > 0 AND base_quantity > 0)
);

CREATE TABLE vendor_invoices (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_id         BIGINT         NOT NULL,
    purchase_order_id BIGINT         NULL,
    -- The vendor's own invoice number.
    invoice_number    VARCHAR(60)    NOT NULL,
    invoice_date      DATE           NOT NULL,
    due_date          DATE           NOT NULL,
    subtotal          DECIMAL(12, 2) NOT NULL,
    -- Input VAT charged by the vendor.
    tax               DECIMAL(12, 2) NOT NULL DEFAULT 0,
    total             DECIMAL(12, 2) NOT NULL,
    paid              DECIMAL(12, 2) NOT NULL DEFAULT 0,
    status            VARCHAR(20)    NOT NULL,
    note              VARCHAR(255)   NULL,
    created_by        BIGINT         NOT NULL,
    created_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_vi_vendor FOREIGN KEY (vendor_id) REFERENCES vendors (id),
    CONSTRAINT fk_vi_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id),
    CONSTRAINT fk_vi_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_vendor_invoice UNIQUE (vendor_id, invoice_number),
    CONSTRAINT ck_vi_amounts CHECK (subtotal >= 0 AND tax >= 0 AND total = subtotal + tax AND paid >= 0 AND paid <= total),
    CONSTRAINT ck_vi_status CHECK (status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    INDEX idx_vi_due (status, due_date)
);

CREATE TABLE vendor_payments (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_invoice_id BIGINT         NOT NULL,
    amount            DECIMAL(12, 2) NOT NULL,
    method            VARCHAR(20)    NOT NULL,
    paid_on           DATE           NOT NULL,
    reference         VARCHAR(100)   NULL,
    note              VARCHAR(255)   NULL,
    created_by        BIGINT         NOT NULL,
    created_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_vp_invoice FOREIGN KEY (vendor_invoice_id) REFERENCES vendor_invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_vp_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_vp_amount CHECK (amount > 0),
    CONSTRAINT ck_vp_method CHECK (method IN ('CASH', 'BANK_TRANSFER', 'KHQR', 'CHEQUE', 'OTHER'))
);

-- Stock history links to the delivery that added the stock.
ALTER TABLE stock_movements
    ADD COLUMN goods_receipt_id BIGINT NULL AFTER adjustment_id,
    ADD CONSTRAINT fk_move_receipt FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts (id) ON DELETE SET NULL;

-- Who may do what: admins everything; managers order and receive; inventory staff receive;
-- accountants handle invoices and payments.
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r JOIN (
    SELECT 'ADMIN' AS code, 'PURCHASE_VIEW' AS permission UNION ALL SELECT 'ADMIN', 'VENDORS_MANAGE'
    UNION ALL SELECT 'ADMIN', 'PURCHASE_ORDER' UNION ALL SELECT 'ADMIN', 'PURCHASE_RECEIVE'
    UNION ALL SELECT 'ADMIN', 'PURCHASE_INVOICE' UNION ALL SELECT 'ADMIN', 'PURCHASE_PAY'
    UNION ALL SELECT 'MANAGER', 'PURCHASE_VIEW' UNION ALL SELECT 'MANAGER', 'VENDORS_MANAGE'
    UNION ALL SELECT 'MANAGER', 'PURCHASE_ORDER' UNION ALL SELECT 'MANAGER', 'PURCHASE_RECEIVE'
    UNION ALL SELECT 'INVENTORY_STAFF', 'PURCHASE_VIEW' UNION ALL SELECT 'INVENTORY_STAFF', 'PURCHASE_RECEIVE'
    UNION ALL SELECT 'ACCOUNTANT', 'PURCHASE_VIEW' UNION ALL SELECT 'ACCOUNTANT', 'PURCHASE_INVOICE'
    UNION ALL SELECT 'ACCOUNTANT', 'PURCHASE_PAY'
) p ON p.code = r.code;
