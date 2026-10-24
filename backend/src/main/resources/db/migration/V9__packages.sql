-- Packages: a catalog entry (type PACKAGE) made of other entries, each with a quantity and unit.
-- Contents can be products, items, services or other packages. The package has its own price,
-- unit, tax and barcode, and no stock of its own: selling it takes the stock of what is inside.
CREATE TABLE package_lines (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id   BIGINT         NOT NULL,
    component_id BIGINT         NOT NULL,
    -- In `unit` (a base or pack unit of the component); jobs or hours for services.
    quantity     DECIMAL(12, 3) NOT NULL,
    unit_id      BIGINT         NULL,
    sort_order   INT            NOT NULL DEFAULT 0,
    CONSTRAINT fk_pline_package FOREIGN KEY (package_id) REFERENCES offerings (id) ON DELETE CASCADE,
    CONSTRAINT fk_pline_component FOREIGN KEY (component_id) REFERENCES offerings (id),
    CONSTRAINT fk_pline_unit FOREIGN KEY (unit_id) REFERENCES units (id),
    CONSTRAINT uq_pline UNIQUE (package_id, component_id),
    CONSTRAINT ck_pline_quantity CHECK (quantity > 0)
);

-- What a sold package contained, as it was at the time, for receipts and order pages.
-- (The stock it used is in order_item_components, like a service's parts.)
CREATE TABLE order_item_contents (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_item_id BIGINT         NOT NULL,
    offering_id   BIGINT         NOT NULL,
    offering_type VARCHAR(10)    NOT NULL,
    name          VARCHAR(150)   NOT NULL,
    -- Total for the whole order line, in `unit`.
    quantity      DECIMAL(12, 3) NOT NULL,
    unit          VARCHAR(20)    NULL,
    -- 0 = directly in the package, 1 = inside a nested package, ...
    depth         INT            NOT NULL DEFAULT 0,
    sort_order    INT            NOT NULL DEFAULT 0,
    CONSTRAINT fk_content_item FOREIGN KEY (order_item_id) REFERENCES order_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_content_offering FOREIGN KEY (offering_id) REFERENCES offerings (id)
);
