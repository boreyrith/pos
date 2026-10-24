-- User management: contact details, sign-in tracking, lockout after repeated failures, and forced
-- password change. Plus an audit log of who did what.
ALTER TABLE users
    ADD COLUMN email                VARCHAR(100) NULL AFTER full_name,
    ADD COLUMN phone                VARCHAR(50)  NULL AFTER email,
    ADD COLUMN last_login_at        TIMESTAMP(6) NULL,
    ADD COLUMN failed_logins        INT          NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         TIMESTAMP(6) NULL,
    ADD COLUMN must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN password_changed_at  TIMESTAMP(6) NULL;

CREATE TABLE audit_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- Who: null for the system, or a failed sign-in with an unknown username.
    user_id     BIGINT        NULL,
    username    VARCHAR(50)   NULL,
    branch_id   BIGINT        NULL,
    action      VARCHAR(40)   NOT NULL,
    category    VARCHAR(20)   NOT NULL,
    entity_type VARCHAR(30)   NULL,
    entity_id   VARCHAR(40)   NULL,
    summary     VARCHAR(500)  NOT NULL,
    -- JSON: {"changes":[{"field","from","to"}], ...}
    details     TEXT          NULL,
    ip          VARCHAR(64)   NULL,
    user_agent  VARCHAR(255)  NULL,
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_audit_time (created_at),
    INDEX idx_audit_user (user_id, created_at),
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_category (category, created_at)
);
