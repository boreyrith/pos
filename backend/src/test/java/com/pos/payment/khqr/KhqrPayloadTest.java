package com.pos.payment.khqr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class KhqrPayloadTest {

    @Test
    void crcMatchesTheCcittFalseCheckValue() {
        assertThat(KhqrPayload.crc16("123456789")).isEqualTo("29B1");
    }

    @Test
    void formatsAmountsPerCurrency() {
        assertThat(KhqrPayload.formatAmount(new BigDecimal("7.50"), KhqrPayload.Currency.USD)).isEqualTo("7.5");
        assertThat(KhqrPayload.formatAmount(new BigDecimal("10.00"), KhqrPayload.Currency.USD)).isEqualTo("10");
        assertThat(KhqrPayload.formatAmount(new BigDecimal("4100.4"), KhqrPayload.Currency.KHR)).isEqualTo("4100");
    }

    @Test
    void buildsADynamicIndividualPayloadWithAValidChecksum() {
        Instant created = Instant.ofEpochMilli(1_760_000_000_000L);
        var result = KhqrPayload.build(new KhqrPayload.Request("jonhsmith@aclb", "Jonh Smith", "Phnom Penh",
                new BigDecimal("1.25"), KhqrPayload.Currency.USD, "20261020-00001", created,
                created.plusSeconds(300)));

        String p = result.payload();
        assertThat(p).startsWith("000201010212" + "2918" + "0014jonhsmith@aclb");
        assertThat(p).contains("5303840", "54041.25", "5802KH", "5910Jonh Smith", "6010Phnom Penh",
                "62180114" + "20261020-00001", "99340013" + created.toEpochMilli());
        String body = p.substring(0, p.length() - 4);
        assertThat(body).endsWith("6304");
        assertThat(p.substring(p.length() - 4)).isEqualTo(KhqrPayload.crc16(body));
        assertThat(result.md5()).hasSize(32);
    }
}
