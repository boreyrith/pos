-- Every catalog entry gets a Khmer name, an optional description and an optional image.
-- Entries made before this have no Khmer name; the app requires one the next time they are saved.
ALTER TABLE offerings
    ADD COLUMN name_km       VARCHAR(150)  NULL AFTER name,
    ADD COLUMN description   VARCHAR(1000) NULL AFTER name_km,
    ADD COLUMN has_image     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Bumped on every image change so browsers fetch the new one.
    ADD COLUMN image_version INT           NOT NULL DEFAULT 0;

-- Images live apart from offerings so listing the catalog never loads image bytes.
CREATE TABLE offering_images (
    offering_id  BIGINT       NOT NULL PRIMARY KEY,
    data         MEDIUMBLOB   NOT NULL,
    content_type VARCHAR(30)  NOT NULL,
    size_bytes   INT          NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_image_offering FOREIGN KEY (offering_id) REFERENCES offerings (id) ON DELETE CASCADE
);

-- Order lines keep the Khmer name they were sold under.
ALTER TABLE order_items ADD COLUMN name_km VARCHAR(150) NULL AFTER name;
ALTER TABLE order_item_contents ADD COLUMN name_km VARCHAR(150) NULL AFTER name;
