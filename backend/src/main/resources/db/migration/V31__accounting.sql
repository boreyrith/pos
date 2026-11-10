-- Accounting: a chart of accounts and a double-entry journal that the rest of the system posts to.
-- Sales, returns, deliveries, vendor invoices and payments, stock adjustments and cost changes post
-- balanced entries automatically, from the start date the accountant sets (balances before it are
-- entered once as opening balances). Other income and expenses (rent, electricity, interest...)
-- are recorded here, and accountants can post their own journal entries. Amounts are in the base
-- currency. Nothing existing is changed: until the books are started, nothing posts.

-- Accounts. The type decides where an account appears (balance sheet or profit and loss) and its
-- normal side. System accounts (system_key set) are the ones automatic postings use; they can be
-- renamed but keep their type. Each branch has its own cash drawer account (system_key CASH:<branch>).
CREATE TABLE accounts (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(10)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    system_key  VARCHAR(30)  NULL UNIQUE,
    branch_id   BIGINT       NULL,
    description VARCHAR(255) NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_account_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT ck_account_type CHECK (type IN ('CASH', 'BANK', 'CURRENT_ASSET', 'FIXED_ASSET', 'CURRENT_LIABILITY',
        'LONG_TERM_LIABILITY', 'EQUITY', 'REVENUE', 'OTHER_INCOME', 'COST_OF_SALES', 'EXPENSE', 'OTHER_EXPENSE'))
);

-- The books' dates: one row (id 1). Nothing posts before start_date (null: not started yet), and
-- nothing can be posted on or before lock_date (a closed period).
CREATE TABLE accounting_settings (
    id         BIGINT       PRIMARY KEY,
    start_date DATE         NULL,
    lock_date  DATE         NULL,
    updated_by BIGINT       NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_acct_settings_user FOREIGN KEY (updated_by) REFERENCES users (id)
);
INSERT INTO accounting_settings (id) VALUES (1);

-- Journal entries. `source` is what made it, `source_id` the document (order, receipt, invoice...)
-- and `reference` its number. `source_key` makes automatic postings happen once per event
-- (e.g. SALE:12, RETURN:4, INVOICE_VOID:7); manual entries and revaluations have none. A reversal
-- points at the entry it cancels; an entry can be reversed once.
CREATE TABLE journal_entries (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_number VARCHAR(20)    NULL UNIQUE,
    entry_date   DATE           NOT NULL,
    branch_id    BIGINT         NULL,
    source       VARCHAR(20)    NOT NULL,
    source_id    BIGINT         NULL,
    source_key   VARCHAR(40)    NULL UNIQUE,
    reference    VARCHAR(60)    NULL,
    memo         VARCHAR(255)   NOT NULL,
    -- Sum of the debits (= sum of the credits).
    total        DECIMAL(14, 2) NOT NULL,
    reversal_of  BIGINT         NULL UNIQUE,
    created_by   BIGINT         NULL,
    created_at   TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_je_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT fk_je_reversal FOREIGN KEY (reversal_of) REFERENCES journal_entries (id),
    CONSTRAINT fk_je_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_je_source CHECK (source IN ('SALE', 'SALE_RETURN', 'GOODS_RECEIPT', 'VENDOR_INVOICE', 'VENDOR_INVOICE_VOID',
        'VENDOR_PAYMENT', 'STOCK_ADJUSTMENT', 'OPENING_STOCK', 'REVALUATION', 'OTHER_INCOME', 'OTHER_EXPENSE', 'MANUAL',
        'OPENING', 'REVERSAL')),
    CONSTRAINT ck_je_total CHECK (total >= 0),
    INDEX idx_je_date (entry_date),
    INDEX idx_je_source (source, source_id)
);

-- One side per line. Vendor and customer say whose payable or sale a line is (for their ledgers).
CREATE TABLE journal_lines (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id    BIGINT         NOT NULL,
    line_no     INT            NOT NULL,
    account_id  BIGINT         NOT NULL,
    debit       DECIMAL(14, 2) NOT NULL DEFAULT 0,
    credit      DECIMAL(14, 2) NOT NULL DEFAULT 0,
    memo        VARCHAR(255)   NULL,
    vendor_id   BIGINT         NULL,
    customer_id BIGINT         NULL,
    CONSTRAINT fk_jl_entry FOREIGN KEY (entry_id) REFERENCES journal_entries (id) ON DELETE CASCADE,
    CONSTRAINT fk_jl_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_jl_vendor FOREIGN KEY (vendor_id) REFERENCES vendors (id),
    CONSTRAINT fk_jl_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_jl_amounts CHECK (debit >= 0 AND credit >= 0 AND (debit = 0 OR credit = 0) AND debit + credit > 0),
    INDEX idx_jl_account (account_id, entry_id),
    INDEX idx_jl_vendor (vendor_id)
);

-- Other income and expenses: money in or out that isn't a sale or a vendor invoice (rent,
-- electricity, salaries, interest, selling scrap...). `amount` is what was paid, tax included;
-- `tax` the VAT in it. Voiding keeps the record and posts a reversal.
CREATE TABLE other_transactions (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    txn_number       VARCHAR(20)    NULL UNIQUE,
    kind             VARCHAR(10)    NOT NULL,
    txn_date         DATE           NOT NULL,
    branch_id        BIGINT         NOT NULL,
    -- The income or expense account, and the cash or bank account the money went through.
    account_id       BIGINT         NOT NULL,
    money_account_id BIGINT         NOT NULL,
    amount           DECIMAL(12, 2) NOT NULL,
    tax              DECIMAL(12, 2) NOT NULL DEFAULT 0,
    -- Who paid or was paid: free text, or a vendor or customer.
    party            VARCHAR(150)   NULL,
    vendor_id        BIGINT         NULL,
    customer_id      BIGINT         NULL,
    reference        VARCHAR(60)    NULL,
    note             VARCHAR(255)   NULL,
    status           VARCHAR(10)    NOT NULL DEFAULT 'POSTED',
    journal_entry_id BIGINT         NULL,
    created_by       BIGINT         NOT NULL,
    created_at       TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    voided_by        BIGINT         NULL,
    voided_at        TIMESTAMP(6)   NULL,
    void_reason      VARCHAR(255)   NULL,
    CONSTRAINT fk_ot_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT fk_ot_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_ot_money FOREIGN KEY (money_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_ot_vendor FOREIGN KEY (vendor_id) REFERENCES vendors (id),
    CONSTRAINT fk_ot_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_ot_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_ot_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_ot_voider FOREIGN KEY (voided_by) REFERENCES users (id),
    CONSTRAINT ck_ot_kind CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_ot_status CHECK (status IN ('POSTED', 'VOID')),
    CONSTRAINT ck_ot_amounts CHECK (amount > 0 AND tax >= 0 AND tax < amount),
    INDEX idx_ot_date (kind, txn_date)
);

-- A vendor invoice without a purchase order (rent, electricity...) is booked to an expense account;
-- a vendor payment says which cash or bank account it was paid from.
ALTER TABLE vendor_invoices
    ADD COLUMN expense_account_id BIGINT NULL AFTER purchase_order_id,
    ADD CONSTRAINT fk_vi_expense FOREIGN KEY (expense_account_id) REFERENCES accounts (id);
ALTER TABLE vendor_payments
    ADD COLUMN money_account_id BIGINT NULL AFTER method,
    ADD CONSTRAINT fk_vp_money FOREIGN KEY (money_account_id) REFERENCES accounts (id);

-- The starting chart of accounts. A cash drawer per branch; the rest is shared.
INSERT INTO accounts (code, name, type, system_key, branch_id, description)
SELECT CONCAT('10', LPAD(id, 2, '0')), CONCAT('Cash - ', name), 'CASH', CONCAT('CASH:', id), id, 'Cash drawer of this branch'
FROM branches;

INSERT INTO accounts (code, name, type, system_key, description) VALUES
    ('1100', 'Bakong (KHQR)', 'BANK', 'KHQR', 'Where KHQR payments are settled'),
    ('1110', 'Bank account', 'BANK', 'BANK', 'Bank transfers and cheques'),
    ('1200', 'Inventory', 'CURRENT_ASSET', 'INVENTORY', 'Stock on hand at cost'),
    ('1300', 'VAT receivable', 'CURRENT_ASSET', 'VAT_INPUT', 'VAT paid on purchases and expenses (input VAT)'),
    ('1500', 'Equipment and furniture', 'FIXED_ASSET', NULL, NULL),
    ('2000', 'Accounts payable', 'CURRENT_LIABILITY', 'PAYABLES', 'What is owed to vendors'),
    ('2100', 'Goods received not invoiced', 'CURRENT_LIABILITY', 'GOODS_NOT_INVOICED', 'Deliveries waiting for the vendor''s invoice'),
    ('2200', 'VAT payable', 'CURRENT_LIABILITY', 'VAT_OUTPUT', 'VAT charged on sales (output VAT)'),
    ('2500', 'Loans', 'LONG_TERM_LIABILITY', NULL, NULL),
    ('3000', 'Owner''s capital', 'EQUITY', NULL, NULL),
    ('3100', 'Opening balances', 'EQUITY', 'OPENING', 'Balances brought in when the books were started'),
    ('3200', 'Retained earnings', 'EQUITY', 'RETAINED', 'Profits of closed years'),
    ('4000', 'Sales', 'REVENUE', 'SALES', 'Sales after discounts, without tax'),
    ('4100', 'Other income', 'OTHER_INCOME', NULL, NULL),
    ('4200', 'Interest income', 'OTHER_INCOME', NULL, NULL),
    ('5000', 'Cost of goods sold', 'COST_OF_SALES', 'COST_OF_SALES', 'Stock sold, at cost'),
    ('5100', 'Stock adjustments', 'COST_OF_SALES', 'STOCK_ADJUSTMENTS', 'Stock found, damaged, lost, counted...'),
    ('5200', 'Purchase price differences', 'COST_OF_SALES', 'PRICE_DIFFERENCES', 'Deliveries priced differently from the catalog cost'),
    ('5300', 'Stock revaluation', 'COST_OF_SALES', 'REVALUATION', 'Stock on hand revalued when a cost changes'),
    ('6000', 'Rent', 'EXPENSE', NULL, NULL),
    ('6100', 'Salaries and wages', 'EXPENSE', NULL, NULL),
    ('6200', 'Electricity and water', 'EXPENSE', NULL, NULL),
    ('6300', 'Transport and delivery', 'EXPENSE', NULL, NULL),
    ('6400', 'Repairs and maintenance', 'EXPENSE', NULL, NULL),
    ('6500', 'Marketing and advertising', 'EXPENSE', NULL, NULL),
    ('6600', 'Shop and office supplies', 'EXPENSE', NULL, NULL),
    ('6700', 'Bank and payment fees', 'EXPENSE', NULL, NULL),
    ('6800', 'Phone and internet', 'EXPENSE', NULL, NULL),
    ('6900', 'General expenses', 'EXPENSE', 'GENERAL_EXPENSES', 'Vendor invoices without an order and no account chosen'),
    ('7000', 'Other expenses', 'OTHER_EXPENSE', NULL, NULL);

-- Who may do what: admins and accountants everything; managers see the books, record income and
-- expenses and read the financial reports.
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r JOIN (
    SELECT 'ADMIN' AS code, 'ACCOUNTING_VIEW' AS permission UNION ALL SELECT 'ADMIN', 'ACCOUNTING_RECORD'
    UNION ALL SELECT 'ADMIN', 'ACCOUNTING_JOURNAL' UNION ALL SELECT 'ADMIN', 'ACCOUNTING_MANAGE'
    UNION ALL SELECT 'ADMIN', 'REPORTS_ACCOUNTING'
    UNION ALL SELECT 'ACCOUNTANT', 'ACCOUNTING_VIEW' UNION ALL SELECT 'ACCOUNTANT', 'ACCOUNTING_RECORD'
    UNION ALL SELECT 'ACCOUNTANT', 'ACCOUNTING_JOURNAL' UNION ALL SELECT 'ACCOUNTANT', 'ACCOUNTING_MANAGE'
    UNION ALL SELECT 'ACCOUNTANT', 'REPORTS_ACCOUNTING'
    UNION ALL SELECT 'MANAGER', 'ACCOUNTING_VIEW' UNION ALL SELECT 'MANAGER', 'ACCOUNTING_RECORD'
    UNION ALL SELECT 'MANAGER', 'REPORTS_ACCOUNTING'
) p ON p.code = r.code;
