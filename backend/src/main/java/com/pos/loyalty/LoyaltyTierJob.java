package com.pos.loyalty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Works tiers out again for everyone as old sales leave the tier window. */
@Component
public class LoyaltyTierJob {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyTierJob.class);

    private final LoyaltyService loyalty;

    public LoyaltyTierJob(LoyaltyService loyalty) {
        this.loyalty = loyalty;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT2M")
    public void run() {
        try {
            int changed = loyalty.refreshAllTiers();
            if (changed > 0) {
                log.info("Loyalty tiers: {} customers moved tier", changed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not refresh loyalty tiers", e);
        }
    }
}
