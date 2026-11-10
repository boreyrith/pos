package com.pos.purchasing;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.inventory.DeliveryLookup;

/** Tells stock history which purchase order and receipt number a delivery was. */
@Component
class Deliveries implements DeliveryLookup {

    private final JdbcClient jdbc;

    Deliveries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, Delivery> byIds(Collection<Long> goodsReceiptIds) {
        if (goodsReceiptIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT id, purchase_order_id, receipt_number FROM goods_receipts WHERE id IN (:ids)")
                .param("ids", goodsReceiptIds)
                .query((rs, n) -> Map.entry(rs.getLong("id"), new Delivery(rs.getLong("purchase_order_id"), rs.getString("receipt_number"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
