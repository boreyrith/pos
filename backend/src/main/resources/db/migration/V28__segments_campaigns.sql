-- Marketing, part 3: customer segments and campaigns.
--
-- A segment is a saved set of rules over customers on file (spend, orders, last visit, joined,
-- points, contact details, categories bought); its members are worked out whenever it is used, so
-- it stays current. A campaign takes a segment's members at that moment, writes each one a
-- personal message and, optionally, a coupon code of their own (single use, theirs alone), and
-- keeps them as recipients. Messages are exported for sending; what each campaign brought in is
-- read from the sales that used its codes.
CREATE TABLE segments (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    -- The rules as JSON (see SegmentRules).
    rules       TEXT         NOT NULL,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_segment_creator FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE TABLE campaigns (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL,
    segment_id      BIGINT        NULL,
    segment_name    VARCHAR(100)  NULL,
    -- How the messages go out. EXPORT: a CSV to send with another tool.
    channel         VARCHAR(12)   NOT NULL DEFAULT 'EXPORT',
    -- With {name}, {code}, {points} and {store} filled in for each customer.
    message         VARCHAR(1000) NOT NULL,
    -- A promotion that needs a code: each recipient gets a code of their own.
    promotion_id    BIGINT        NULL,
    code_prefix     VARCHAR(12)   NULL,
    codes_expire_on DATE          NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'DRAFT',
    recipients      INT           NOT NULL DEFAULT 0,
    prepared_by     BIGINT        NULL,
    prepared_at     TIMESTAMP(6)  NULL,
    created_by      BIGINT        NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_campaign_segment FOREIGN KEY (segment_id) REFERENCES segments (id) ON DELETE SET NULL,
    CONSTRAINT fk_campaign_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_campaign_preparer FOREIGN KEY (prepared_by) REFERENCES users (id),
    CONSTRAINT fk_campaign_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_campaign_channel CHECK (channel IN ('EXPORT')),
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT', 'PREPARED'))
);

-- Who a campaign went to, as they were then, with their message and code.
CREATE TABLE campaign_recipients (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id    BIGINT        NOT NULL,
    customer_id    BIGINT        NOT NULL,
    name           VARCHAR(150)  NOT NULL,
    phone          VARCHAR(30)   NULL,
    email          VARCHAR(100)  NULL,
    coupon_code_id BIGINT        NULL,
    message        VARCHAR(1200) NOT NULL,
    CONSTRAINT fk_recipient_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
    CONSTRAINT fk_recipient_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_recipient_coupon FOREIGN KEY (coupon_code_id) REFERENCES coupon_codes (id),
    INDEX idx_recipient_campaign (campaign_id)
);

INSERT INTO role_permissions (role_id, permission)
SELECT id, 'CAMPAIGNS_MANAGE' FROM roles WHERE code IN ('ADMIN', 'MANAGER');
