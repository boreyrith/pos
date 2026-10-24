package com.pos.config;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stops a non-development start that would be unsafe: no signing key of its own means anyone with
 * the source code could forge sign-ins. Other risky settings are logged as warnings.
 */
@Component
public class ProductionGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionGuard.class);

    private final Environment environment;
    private final AppProperties props;

    public ProductionGuard(Environment environment, AppProperties props) {
        this.environment = environment;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        String[] active = environment.getActiveProfiles().length > 0 ? environment.getActiveProfiles() : environment.getDefaultProfiles();
        if (Arrays.asList(active).contains("dev")) {
            return;
        }
        if (!environment.containsProperty("JWT_SECRET")) {
            throw new IllegalStateException("JWT_SECRET is not set. Outside development the API needs its own signing key: "
                    + "openssl rand -base64 48 (see deploy/DEPLOY.md).");
        }
        if (props.secretsKey() == null || props.secretsKey().isBlank()) {
            log.warn("SECRETS_KEY is not set: a Bakong API token can't be saved in Settings until it is.");
        }
        if (!props.jwt().refreshCookieSecure()) {
            log.warn("REFRESH_COOKIE_SECURE is off: set it to true once the site is served over HTTPS.");
        }
    }
}
