package com.pos.hold;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires old holds and releases abandoned locks. Kept off the controller, whose permission check needs a signed-in user. */
@Component
public class HeldSaleHousekeepingJob {

    private final HeldSaleService service;

    public HeldSaleHousekeepingJob(HeldSaleService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void run() {
        service.housekeeping();
    }
}
