-- "Remember me" on the sign-in form. Remembered sessions keep their refresh cookie for its full
-- lifetime (7 days by default); the others get a browser-session cookie, so closing the browser
-- signs out (for shared tills). Each refresh keeps the choice made at sign-in. Existing sessions were
-- all remembered.
ALTER TABLE user_sessions ADD COLUMN remember BOOLEAN NOT NULL DEFAULT TRUE AFTER expires_at;
