-- Each person's interface language: English (the default) or Khmer.
ALTER TABLE users
    ADD COLUMN language VARCHAR(5) NOT NULL DEFAULT 'en' AFTER phone,
    ADD CONSTRAINT chk_user_language CHECK (language IN ('en', 'km'));
