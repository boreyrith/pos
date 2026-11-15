package com.pos.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.pos.settings.SettingsService;

/** Test payments never reach real customers: outside development they close the shop instead. */
class ShopPaymentModeTest {

    private static ShopPaymentMode mode(boolean simulated, String... profiles) {
        SettingsService settings = mock(SettingsService.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(settings.khqr().simulated()).thenReturn(simulated);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new ShopPaymentMode(settings, env);
    }

    @Test
    void inProductionTestPaymentsCloseTheShop() {
        ShopPaymentMode prod = mode(true, "prod");
        assertThat(prod.blocked()).isTrue();
        assertThat(prod.customersMaySimulate()).isFalse();
    }

    @Test
    void inProductionLivePaymentsOpenIt() {
        ShopPaymentMode prod = mode(false, "prod");
        assertThat(prod.blocked()).isFalse();
        assertThat(prod.customersMaySimulate()).isFalse();
    }

    @Test
    void inDevelopmentCustomersMayPayWithTheTestButton() {
        ShopPaymentMode dev = mode(true, "dev");
        assertThat(dev.blocked()).isFalse();
        assertThat(dev.customersMaySimulate()).isTrue();
    }
}
