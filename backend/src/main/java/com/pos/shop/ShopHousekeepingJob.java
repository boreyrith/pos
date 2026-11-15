package com.pos.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes shop sign-ins that ended or expired over a month ago, hourly (as AuthHousekeepingJob does for staff). */
@Component
public class ShopHousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(ShopHousekeepingJob.class);

    private final CustomerAuthService auth;

    public ShopHousekeepingJob(CustomerAuthService auth) {
        this.auth = auth;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT7M")
    public void run() {
        int removed = auth.deleteOldSessions();
        if (removed > 0) {
            log.info("Removed {} old shop sessions", removed);
        }
    }
}
