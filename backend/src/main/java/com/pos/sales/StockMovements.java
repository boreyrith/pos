package com.pos.sales;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The stock an order takes, per stocked catalog entry: products and items sold directly, plus the
 * parts used by services. Keyed and sorted by id so rows are always locked in the same order,
 * which keeps concurrent checkouts from deadlocking.
 */
final class StockMovements {

    private StockMovements() {
    }

    static Map<Long, Integer> of(List<OrderItem> items) {
        Map<Long, Integer> totals = new TreeMap<>();
        for (OrderItem line : items) {
            if (line.getOfferingType().tracksStock()) {
                totals.merge(line.getOffering().getId(), line.getQuantity(), Integer::sum);
            }
            for (OrderItemComponent used : line.getComponents()) {
                totals.merge(used.getItem().getId(), used.getQuantity(), Integer::sum);
            }
        }
        return totals;
    }

    /**
     * The stock {@code qty} returned units of a line give back: the entry itself, plus its parts
     * scaled by qty / line quantity. Parts are split so the line's last units return whatever is left.
     */
    static Map<Long, Integer> forReturn(OrderItem line, int qty) {
        Map<Long, Integer> totals = new TreeMap<>();
        if (line.getOfferingType().tracksStock()) {
            totals.merge(line.getOffering().getId(), qty, Integer::sum);
        }
        int before = line.getRefundedQty();
        for (OrderItemComponent used : line.getComponents()) {
            long n = (long) used.getQuantity() * (before + qty) / line.getQuantity()
                    - (long) used.getQuantity() * before / line.getQuantity();
            if (n > 0) {
                totals.merge(used.getItem().getId(), (int) n, Integer::sum);
            }
        }
        return totals;
    }
}
