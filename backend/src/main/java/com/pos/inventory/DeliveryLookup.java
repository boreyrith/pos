package com.pos.inventory;

import java.util.Collection;
import java.util.Map;

/**
 * What stock history shows about the vendor deliveries behind PURCHASE movements. Purchasing owns
 * deliveries and implements this, so inventory doesn't depend on purchasing.
 */
public interface DeliveryLookup {

    record Delivery(Long purchaseOrderId, String receiptNumber) {
    }

    /** The deliveries with these ids, by id. */
    Map<Long, Delivery> byIds(Collection<Long> goodsReceiptIds);
}
