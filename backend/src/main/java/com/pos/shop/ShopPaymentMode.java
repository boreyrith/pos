package com.pos.shop;

import java.util.Arrays;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.pos.settings.SettingsService;

/**
 * Test (simulated) KHQR payments and the online shop. With test payments anyone could mark an order
 * paid, so outside development the shop takes orders only with live KHQR, and customers never get the
 * "Simulate payment" button. Development is the {@code dev} profile, as for ProductionGuard.
 */
@Component
public class ShopPaymentMode {

    private final SettingsService settings;
    private final boolean development;

    public ShopPaymentMode(SettingsService settings, Environment environment) {
        this.settings = settings;
        String[] active = environment.getActiveProfiles().length > 0 ? environment.getActiveProfiles() : environment.getDefaultProfiles();
        this.development = Arrays.asList(active).contains("dev");
    }

    /** KHQR payments are simulated (Settings > Taxes & payments). */
    boolean testPayments() {
        return settings.khqr().simulated();
    }

    /** The shop can't take orders: test payments outside development. */
    boolean blocked() {
        return testPayments() && !development;
    }

    /** Customers may pay with the test button: test payments, in development only. */
    boolean customersMaySimulate() {
        return testPayments() && development;
    }
}
