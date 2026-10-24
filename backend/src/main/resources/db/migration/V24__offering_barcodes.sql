-- An entry can have several barcodes (e.g. the maker's EAN and an in-store code). Each code is unique
-- across the catalog. offerings.barcode keeps the first one, used on labels.
CREATE TABLE offering_barcodes (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    offering_id BIGINT      NOT NULL,
    code        VARCHAR(64) NOT NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_offering_barcode_code UNIQUE (code),
    CONSTRAINT fk_offering_barcode_offering FOREIGN KEY (offering_id) REFERENCES offerings (id) ON DELETE CASCADE,
    INDEX idx_offering_barcode_offering (offering_id, sort_order)
);

INSERT INTO offering_barcodes (offering_id, code, sort_order)
SELECT id, barcode, 0 FROM offerings WHERE barcode IS NOT NULL AND barcode <> '';
