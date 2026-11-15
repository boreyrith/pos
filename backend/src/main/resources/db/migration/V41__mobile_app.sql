-- The mobile cashier app's download page (frontend-app, /app): what admins set under Settings > Mobile
-- app. The Android install file (APK) is kept in the database, like product pictures and the logo, so
-- one backup holds everything; it has its own table so reading the details never loads the file. Store
-- links (Google Play, App Store, TestFlight) are optional, for when the app is published there.
CREATE TABLE mobile_app (
    id              TINYINT        NOT NULL PRIMARY KEY,
    version_name    VARCHAR(30)    NULL,
    release_notes   VARCHAR(2000)  NULL,
    play_store_url  VARCHAR(255)   NULL,
    app_store_url   VARCHAR(255)   NULL,
    testflight_url  VARCHAR(255)   NULL,
    apk_file_name   VARCHAR(120)   NULL,
    apk_size        BIGINT         NULL,
    apk_sha256      VARCHAR(64)    NULL,
    apk_uploaded_at TIMESTAMP(6)   NULL,
    apk_downloads   INT            NOT NULL DEFAULT 0,
    updated_by      BIGINT         NULL,
    updated_at      TIMESTAMP(6)   NOT NULL,
    CONSTRAINT ck_mobile_app_single_row CHECK (id = 1),
    CONSTRAINT ck_mobile_app_downloads CHECK (apk_downloads >= 0),
    CONSTRAINT ck_mobile_app_apk CHECK ((apk_file_name IS NULL) = (apk_size IS NULL) AND (apk_size IS NULL) = (apk_sha256 IS NULL)),
    CONSTRAINT fk_mobile_app_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
INSERT INTO mobile_app (id, updated_at) VALUES (1, CURRENT_TIMESTAMP(6));

CREATE TABLE mobile_app_apk (
    id    TINYINT   NOT NULL PRIMARY KEY,
    data  LONGBLOB  NOT NULL,
    CONSTRAINT ck_mobile_app_apk_single_row CHECK (id = 1)
);
