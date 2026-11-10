package com.pos.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes sign-in records nobody can use any more. Each renewal leaves a refresh token behind (kept
 * until it expires, so a stolen old token is recognised), which is about 40 rows a day per device in
 * use; expired ones are deleted after a day. Sessions are kept 90 days after they ended or expired,
 * for the record, then deleted. The audit log keeps sign-ins and sign-outs regardless.
 */
@Component
public class AuthHousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(AuthHousekeepingJob.class);
    static final Duration KEEP_EXPIRED_TOKENS = Duration.ofDays(1);
    static final Duration KEEP_SESSIONS = Duration.ofDays(90);

    private final JdbcClient jdbc;

    public AuthHousekeepingJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void run() {
        try {
            int[] removed = purge(Instant.now());
            if (removed[0] + removed[1] > 0) {
                log.info("Removed {} expired refresh tokens and {} old sessions", removed[0], removed[1]);
            }
        } catch (RuntimeException e) {
            log.warn("Could not clean up sign-in records", e);
        }
    }

    /** Returns how many refresh tokens and sessions were removed. */
    @Transactional
    public int[] purge(Instant now) {
        int tokens = jdbc.sql("DELETE FROM refresh_tokens WHERE expires_at < :before")
                .param("before", Timestamp.from(now.minus(KEEP_EXPIRED_TOKENS))).update();
        Timestamp old = Timestamp.from(now.minus(KEEP_SESSIONS));
        // A session's tokens never outlive it, so by now none point at it; checked anyway.
        int sessions = jdbc.sql("""
                        DELETE FROM user_sessions
                        WHERE (ended_at < :old OR expires_at < :old)
                          AND NOT EXISTS (SELECT 1 FROM refresh_tokens t WHERE t.session_id = user_sessions.id)
                        """)
                .param("old", old).update();
        return new int[] {tokens, sessions};
    }
}
