-- Company information and system-wide settings, edited in the app (Settings > Company).
-- One row. The backend fills it from the environment defaults the first time it starts.
-- The Bakong API token and the KHQR mode stay in the environment: they are secrets / deployment choices.
CREATE TABLE company_settings (
    id                 TINYINT        NOT NULL PRIMARY KEY,
    name               VARCHAR(100)   NOT NULL,
    legal_name         VARCHAR(150)   NULL,
    tax_id             VARCHAR(50)    NULL,
    address            VARCHAR(255)   NULL,
    phone              VARCHAR(50)    NULL,
    email              VARCHAR(100)   NULL,
    website            VARCHAR(150)   NULL,
    receipt_footer     VARCHAR(500)   NULL,
    vat_rate           DECIMAL(5, 4)  NOT NULL,
    timezone           VARCHAR(50)    NOT NULL,
    khqr_account_id    VARCHAR(100)   NOT NULL,
    khqr_merchant_name VARCHAR(25)    NOT NULL,
    khqr_merchant_city VARCHAR(15)    NOT NULL,
    logo               MEDIUMBLOB     NULL,
    logo_type          VARCHAR(30)    NULL,
    logo_version       INT            NOT NULL DEFAULT 0,
    updated_by         BIGINT         NULL,
    updated_at         TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_single_row CHECK (id = 1),
    CONSTRAINT ck_vat_rate CHECK (vat_rate >= 0 AND vat_rate < 1),
    CONSTRAINT fk_settings_user FOREIGN KEY (updated_by) REFERENCES users (id)
);
