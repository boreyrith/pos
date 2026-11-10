package com.pos.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.common.AppProperties;

/**
 * Sessions ended in the last access-token lifetime, so their access tokens are refused at once rather
 * than working until they expire. Kept in memory (one backend instance) and reloaded from the database
 * when the server starts.
 */
@Component
public class EndedSessions implements ApplicationRunner {

    private final Map<String, Instant> ended = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;
    private final AppProperties props;

    public EndedSessions(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public void add(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Instant now = Instant.now();
        ended.put(sessionId, now.plus(props.jwt().accessTokenTtl()));
        // Entries are only needed until their access tokens expire: drop the old ones as new ones come.
        if (ended.size() > 1000) {
            ended.values().removeIf(until -> until.isBefore(now));
        }
    }

    public boolean contains(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Instant until = ended.get(sessionId);
        if (until == null) {
            return false;
        }
        if (until.isBefore(Instant.now())) {
            // Every access token of it has expired by now.
            ended.remove(sessionId);
            return false;
        }
        return true;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant since = Instant.now().minus(props.jwt().accessTokenTtl());
        jdbc.sql("SELECT id FROM user_sessions WHERE ended_at >= :since").param("since", java.sql.Timestamp.from(since))
                .query(String.class).list().forEach(this::add);
    }
}
