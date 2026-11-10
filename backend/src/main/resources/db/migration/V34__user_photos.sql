-- Each person can add a photo of themselves to their account (My account), shown in the menu and on
-- the account dialog instead of their initials. Photos live apart from users so loading users never
-- loads image bytes, like catalog images (V10).
ALTER TABLE users
    ADD COLUMN has_photo     BOOLEAN NOT NULL DEFAULT FALSE,
    -- Bumped on every photo change so the app fetches the new one.
    ADD COLUMN photo_version INT     NOT NULL DEFAULT 0;

CREATE TABLE user_photos (
    user_id      BIGINT       NOT NULL PRIMARY KEY,
    data         MEDIUMBLOB   NOT NULL,
    content_type VARCHAR(30)  NOT NULL,
    size_bytes   INT          NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_user_photo_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_photo_size CHECK (size_bytes BETWEEN 1 AND 2097152),
    CONSTRAINT ck_user_photo_type CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp'))
);
