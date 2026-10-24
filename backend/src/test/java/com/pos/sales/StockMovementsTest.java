package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pos.catalog.Offering;
import com.pos.catalog.OfferingType;

class StockMovementsTest {

    private static Offering offering(long id, OfferingType type) {
        Offering o = new Offering(type);
        o.setId(id);
        o.setName("#" + id);
        return o;
    }

    private static OrderItem line(Offering offering, int quantity) {
        OrderItem item = new OrderItem();
        item.setOffering(offering);
        item.setOfferingType(offering.getType());
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void addsUpDirectSalesAndPartsUsedByServices() {
        Offering coffee = offering(30, OfferingType.PRODUCT);
        Offering screen = offering(10, OfferingType.ITEM);
        Offering repair = offering(20, OfferingType.SERVICE);

        OrderItem repairLine = line(repair, 2);
        repairLine.addComponent(screen, 2); // two repairs, one screen each
        List<OrderItem> lines = List.of(line(coffee, 3), line(screen, 1), repairLine);

        Map<Long, Integer> movements = StockMovements.of(lines);

        assertThat(movements).containsExactly(Map.entry(10L, 3), Map.entry(30L, 3));
    }

    @Test
    void servicesWithoutPartsMoveNoStock() {
        assertThat(StockMovements.of(List.of(line(offering(5, OfferingType.SERVICE), 1)))).isEmpty();
    }
}
