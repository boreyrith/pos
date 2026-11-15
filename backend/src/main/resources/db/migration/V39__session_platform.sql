-- Each session belongs to one platform: administration (CTL, /ctl), the till (CAS, /cas) or the mobile
-- app (APP). Each platform has its own refresh cookie, so signing in or out of one leaves the others,
-- and the lists of signed-in devices group sessions by it. Existing sessions: the mobile app's (its
-- HTTP client is Dart) are APP; the web ones were the single web app, which is now administration.
ALTER TABLE user_sessions ADD COLUMN platform VARCHAR(3) NOT NULL DEFAULT 'CTL' AFTER user_id;
UPDATE user_sessions SET platform = 'APP' WHERE user_agent LIKE 'Dart%';
ALTER TABLE user_sessions
    ALTER COLUMN platform DROP DEFAULT,
    ADD CONSTRAINT ck_session_platform CHECK (platform IN ('CTL', 'CAS', 'APP'));
