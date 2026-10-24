-- Units of measure. Products and items keep stock in a base unit and may have pack units that
-- hold a whole number of base units (1 box = 20 pcs). Services have no unit.
CREATE TABLE units (
    id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL UNIQUE,
    name   VARCHAR(50) NOT NULL
);

INSERT INTO units (symbol, name) VALUES
    ('pcs', 'Piece'), ('box', 'Box'), ('pack', 'Pack'), ('carton', 'Carton'), ('bottle', 'Bottle'),
    ('can', 'Can'), ('set', 'Set'), ('pair', 'Pair'), ('roll', 'Roll'), ('kg', 'Kilogram'), ('g', 'Gram'),
    ('L', 'Litre'), ('ml', 'Millilitre'), ('m', 'Metre');

-- Keep any unit typed in before this migration.
INSERT INTO units (symbol, name)
SELECT DISTINCT o.unit, o.unit FROM offerings o
WHERE o.unit IS NOT NULL AND o.unit <> '' AND o.unit NOT IN (SELECT symbol FROM units);

ALTER TABLE offerings
    ADD COLUMN unit_id BIGINT NULL AFTER unit,
    ADD CONSTRAINT fk_offering_unit FOREIGN KEY (unit_id) REFERENCES units (id);

UPDATE offerings o JOIN units u ON u.symbol = o.unit SET o.unit_id = u.id;
UPDATE offerings SET unit_id = (SELECT id FROM units WHERE symbol = 'pcs')
WHERE type <> 'SERVICE' AND unit_id IS NULL;
UPDATE offerings SET unit_id = NULL WHERE type = 'SERVICE';

ALTER TABLE offerings DROP COLUMN unit;

CREATE TABLE offering_packs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    offering_id BIGINT NOT NULL,
    unit_id     BIGINT NOT NULL,
    -- How many base units one of this pack holds.
    factor      INT    NOT NULL,
    CONSTRAINT fk_pack_offering FOREIGN KEY (offering_id) REFERENCES offerings (id) ON DELETE CASCADE,
    CONSTRAINT fk_pack_unit FOREIGN KEY (unit_id) REFERENCES units (id),
    CONSTRAINT uq_pack UNIQUE (offering_id, unit_id),
    CONSTRAINT ck_pack_factor CHECK (factor >= 2)
);
