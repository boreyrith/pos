package com.pos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pos.common.ApiException;

class PackageExpanderTest {

    private final Unit pcs = unit(1L, "pcs");
    private final Unit box = unit(2L, "box");

    private static Unit unit(long id, String symbol) {
        Unit u = new Unit(symbol, symbol);
        u.setId(id);
        return u;
    }

    private Offering entry(long id, OfferingType type, String name) {
        Offering o = new Offering(type);
        o.setId(id);
        o.setName(name);
        o.setPrice(BigDecimal.ONE);
        if (type.tracksStock()) {
            o.setUnit(pcs);
        }
        return o;
    }

    private static void add(Offering pkg, Offering component, String qty, Unit unit) {
        pkg.getPackageLines().add(new PackageLine(pkg, component, new BigDecimal(qty), unit, pkg.getPackageLines().size()));
    }

    @Test
    void expandsProductsInPackUnitsAndServiceParts() {
        Offering cable = entry(1, OfferingType.ITEM, "Cable");
        cable.getPacks().add(new OfferingPack(cable, box, 20));
        Offering screen = entry(2, OfferingType.ITEM, "Screen");
        Offering repair = entry(3, OfferingType.SERVICE, "Repair");
        repair.getComponents().add(new ServiceComponent(repair, screen, 1));
        Offering pkg = entry(10, OfferingType.PACKAGE, "Repair bundle");
        add(pkg, cable, "1", box);     // 20 cables
        add(pkg, repair, "2", null);   // 2 repairs, each uses 1 screen

        var x = PackageExpander.expand(pkg, 3);

        assertThat(x.stock()).containsEntry(1L, 60).containsEntry(2L, 6);
        assertThat(x.containsServices()).isTrue();
        assertThat(x.contents()).extracting(PackageExpander.Content::quantity)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("3"), new BigDecimal("6"));
    }

    @Test
    void expandsNestedPackages() {
        Offering coffee = entry(1, OfferingType.PRODUCT, "Coffee");
        Offering inner = entry(10, OfferingType.PACKAGE, "Duo");
        add(inner, coffee, "2", pcs);
        Offering outer = entry(11, OfferingType.PACKAGE, "Party");
        add(outer, inner, "3", null);

        var x = PackageExpander.expand(outer, 1);

        assertThat(x.stock()).containsExactly(Map.entry(1L, 6));
        assertThat(x.contents()).extracting(PackageExpander.Content::depth).containsExactly(0, 1);
    }

    @Test
    void refusesAPackageThatContainsItself() {
        Offering a = entry(10, OfferingType.PACKAGE, "A");
        Offering b = entry(11, OfferingType.PACKAGE, "B");
        add(a, b, "1", null);
        add(b, a, "1", null);
        assertThatThrownBy(() -> PackageExpander.expand(a, 1)).isInstanceOf(ApiException.class).hasMessageContaining("contains itself");
    }

    @Test
    void refusesFractionsOfAStockUnit() {
        Offering coffee = entry(1, OfferingType.PRODUCT, "Coffee");
        Offering pkg = entry(10, OfferingType.PACKAGE, "Half");
        add(pkg, coffee, "0.5", pcs);
        assertThatThrownBy(() -> PackageExpander.expand(pkg, 1)).hasMessageContaining("whole pcs");
        // ...but two half-portions make a whole one.
        assertThat(PackageExpander.expand(pkg, 2).stock()).containsEntry(1L, 1);
    }

    @Test
    void availabilityIsLimitedByTheScarcestContent() {
        assertThat(PackageExpander.available(Map.of(1L, 2, 2L, 1), Map.of(1L, 9, 2L, 3))).isEqualTo(3);
        assertThat(PackageExpander.available(Map.of(1L, 2), Map.of())).isZero();
        assertThat(PackageExpander.available(Map.of(), Map.of())).isNull();
    }
}
