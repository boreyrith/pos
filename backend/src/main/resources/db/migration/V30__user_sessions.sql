-- Signed-in sessions. Each sign-in is a session: its refresh tokens (one after another as they
-- rotate) all belong to it, and its access tokens carry its id ("sid"). Staff see and end their own
-- sessions; the super admin sees and ends anyone's. An ended session's refresh token stops working
-- and its access token is refused at once, so the device is signed out on its next request.
CREATE TABLE user_sessions (
    id           VARCHAR(36)  PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_used_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- When it ends if nobody uses it: the latest refresh token's expiry.
    expires_at   TIMESTAMP(6) NOT NULL,
    -- Where it was last used from (as reported by the proxy in front), and the browser.
    ip_address   VARCHAR(45)  NULL,
    user_agent   VARCHAR(255) NULL,
    ended_at     TIMESTAMP(6) NULL,
    ended_by     BIGINT       NULL,
    -- SIGNED_OUT, ENDED (by the user from another device), ADMIN, PASSWORD_CHANGED, ACCOUNT_CHANGED, TOKEN_REUSED
    end_reason   VARCHAR(20)  NULL,
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_session_ender FOREIGN KEY (ended_by) REFERENCES users (id),
    INDEX idx_session_user_active (user_id, ended_at, expires_at),
    INDEX idx_session_ended (ended_at)
);

ALTER TABLE refresh_tokens
    ADD COLUMN session_id VARCHAR(36) NULL AFTER user_id,
    ADD CONSTRAINT fk_refresh_session FOREIGN KEY (session_id) REFERENCES user_sessions (id);
