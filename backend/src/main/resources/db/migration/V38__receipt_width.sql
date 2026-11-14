-- The receipt's paper width in mm (Settings > Company): 80mm or 58mm thermal rolls. The web and
-- mobile apps lay the receipt out for it and print it at that width. Existing stores keep 80mm.
ALTER TABLE company_settings
    ADD COLUMN receipt_width INT NOT NULL DEFAULT 80 AFTER receipt_footer,
    ADD CONSTRAINT ck_receipt_width CHECK (receipt_width IN (58, 80));
