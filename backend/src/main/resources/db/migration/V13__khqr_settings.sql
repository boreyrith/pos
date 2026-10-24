-- KHQR / Bakong connection settings, edited under Settings > KHQR payments. NULL means "use the
-- server default" (KHQR_MODE, BAKONG_API_URL, BAKONG_TOKEN, the 5-minute expiry), so existing
-- installs keep working exactly as configured until an admin saves the tab.
ALTER TABLE company_settings
    ADD COLUMN khqr_mode             VARCHAR(10)     NULL AFTER khqr_merchant_city,
    ADD COLUMN khqr_api_base_url     VARCHAR(255)    NULL AFTER khqr_mode,
    -- AES-GCM encrypted with the server's secrets key; never sent to the browser.
    ADD COLUMN khqr_api_token        VARBINARY(4096) NULL AFTER khqr_api_base_url,
    ADD COLUMN khqr_token_updated_at TIMESTAMP(6)    NULL AFTER khqr_api_token,
    ADD COLUMN khqr_expiry_minutes   INT             NULL AFTER khqr_token_updated_at,
    ADD CONSTRAINT ck_khqr_mode CHECK (khqr_mode IN ('SIMULATED', 'BAKONG')),
    ADD CONSTRAINT ck_khqr_expiry CHECK (khqr_expiry_minutes BETWEEN 1 AND 60);
