-- When a refresh token was replaced by a newer one. A token reused shortly after (the browser lost
-- the response, e.g. a reload during a refresh) is not treated as theft. Signing out and revoking
-- sessions clear it, so a revoked token never gets that grace.
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMP(6) NULL AFTER revoked;
