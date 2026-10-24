package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.pos.sales.PricingCalculator.Line;
import com.pos.sales.PricingCalculator.TaxKey;
import com.pos.sales.PricingCalculator.Totals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the shared cases in pricing-cases.json, whose expected values come from an independent
 * reference calculation. The web client's lib/pricing.test.ts runs the same file, so the cart and
 * the server are proven to charge the same amounts.
 */
class PricingFixtureTest {

    static Stream<Arguments> cases() throws Exception {
        try (InputStream in = PricingFixtureTest.class.getResourceAsStream("/pricing-cases.json")) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            List<Arguments> out = new ArrayList<>();
            root.forEach(c -> out.add(Arguments.of(c.get("name").asString(), c)));
            return out.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesReference(String name, JsonNode c) {
        List<Line> lines = new ArrayList<>();
        for (JsonNode l : c.get("lines")) {
            JsonNode t = l.get("tax");
            BigDecimal rate = new BigDecimal(t.get("rate").asString());
            lines.add(new Line(new BigDecimal(l.get("unitPrice").asString()), l.get("quantity").asInt(),
                    l.get("hours").isNull() ? null : new BigDecimal(l.get("hours").asString()),
                    // Like TaxService: untaxed lines carry BigDecimal.ZERO.
                    new TaxKey(t.get("rateId").asLong(), t.get("name").asString(), rate.signum() == 0 ? BigDecimal.ZERO : rate,
                            t.get("included").asBoolean()),
                    l.has("discountType") ? DiscountType.valueOf(l.get("discountType").asString()) : null,
                    l.has("discountValue") ? new BigDecimal(l.get("discountValue").asString()) : null));
        }
        DiscountType type = c.get("discountType").isNull() ? null : DiscountType.valueOf(c.get("discountType").asString());
        BigDecimal value = c.get("discountValue").isNull() ? null : new BigDecimal(c.get("discountValue").asString());

        Totals t = PricingCalculator.calculate(lines, type, value);
        JsonNode e = c.get("expected");
        assertThat(t.subtotal()).as("subtotal").isEqualByComparingTo(e.get("subtotal").asString());
        assertThat(t.discount()).as("discount").isEqualByComparingTo(e.get("discount").asString());
        assertThat(t.itemDiscount()).as("item discount").isEqualByComparingTo(e.get("itemDiscount").asString());
        assertThat(t.taxAdded()).as("tax added").isEqualByComparingTo(e.get("taxAdded").asString());
        assertThat(t.taxIncluded()).as("tax included").isEqualByComparingTo(e.get("taxIncluded").asString());
        assertThat(t.total()).as("total").isEqualByComparingTo(e.get("total").asString());
        assertThat(t.taxableAmount()).as("taxable").isEqualByComparingTo(e.get("taxableAmount").asString());
        assertThat(t.nonTaxableAmount()).as("non-taxable").isEqualByComparingTo(e.get("nonTaxableAmount").asString());
        for (int i = 0; i < lines.size(); i++) {
            JsonNode el = e.get("lines").get(i);
            var split = t.lines().get(i);
            assertThat(split.discount()).as("line %d discount", i).isEqualByComparingTo(el.get("discount").asString());
            assertThat(split.itemDiscount()).as("line %d item discount", i).isEqualByComparingTo(el.get("itemDiscount").asString());
            assertThat(split.net()).as("line %d net", i).isEqualByComparingTo(el.get("net").asString());
            assertThat(split.tax()).as("line %d tax", i).isEqualByComparingTo(el.get("tax").asString());
            assertThat(split.paid()).as("line %d paid", i).isEqualByComparingTo(el.get("paid").asString());
        }
        // Lines always add up to the order: no cent lost or charged twice.
        assertThat(t.lines().stream().map(PricingCalculator.LineSplit::paid).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(t.total());
        assertThat(t.lines().stream().map(PricingCalculator.LineSplit::tax).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(t.tax());
    }
}
