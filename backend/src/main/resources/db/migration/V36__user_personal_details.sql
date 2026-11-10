-- More about each person on a user account: their name in Khmer script and in Latin letters (as on
-- their ID card), gender and date of birth. All optional. The display name (full_name, shown on
-- receipts and in history) stays as it is; people can edit these details themselves in My account.
ALTER TABLE users
    ADD COLUMN name_km       VARCHAR(100) NULL AFTER full_name,
    ADD COLUMN name_latin    VARCHAR(100) NULL AFTER name_km,
    ADD COLUMN gender        VARCHAR(10)  NULL AFTER name_latin,
    ADD COLUMN date_of_birth DATE         NULL AFTER gender,
    ADD CONSTRAINT ck_user_gender CHECK (gender IN ('MALE', 'FEMALE')),
    ADD CONSTRAINT ck_user_birth CHECK (date_of_birth >= '1900-01-01');
