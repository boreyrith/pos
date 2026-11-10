-- Two ways to keep the books. NORMAL: income, expenses and profit without accounting knowledge (no
-- opening balances, manual journal entries, balance sheet or trial balance); the journal and the
-- chart of accounts can still be read. FULL: everything. Sales, purchases and stock post the same
-- in both, so switching to FULL later loses nothing (opening balances are entered then).
ALTER TABLE accounting_settings
    ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'NORMAL' AFTER lock_date,
    ADD CONSTRAINT ck_acct_mode CHECK (mode IN ('NORMAL', 'FULL'));
