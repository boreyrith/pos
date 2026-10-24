package com.pos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.pos.tax.TaxMode;
import com.pos.catalog.OfferingType;
import com.pos.payment.PaymentDto;
import com.pos.payment.PaymentMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SalesDtos {

    private SalesDtos() {
    }

    /**
     * @param hours   hours worked; required for hourly services, not allowed otherwise
     * @param staffId who does the work; services only, defaults to the cashier
     */
    /**
     * @param unitPrice   a price other than the catalog's (per unit, or per hour); needs the
     *                    "Override prices" permission and a {@code priceReason}. Null sells at the catalog price.
     * @param priceReason why the price was changed
     */
    public record CartLine(@NotNull Long offeringId, @Min(1) @Max(10_000) int quantity,
                           @DecimalMin(value = "0", inclusive = false) @DecimalMax("999.99")
                           @Digits(integer = 3, fraction = 2) BigDecimal hours,
                           Long staffId,
                           @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
                           @Size(max = 255) String priceReason,
                           /** An item discount: % of the line or an amount; needs "Discount items". */
                           DiscountType discountType,
                           @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountValue) {
    }

    public record CheckoutRequest(
            @NotEmpty @Size(max = 200) List<@Valid CartLine> items,
            DiscountType discountType,
            @DecimalMin("0") BigDecimal discountValue,
            @NotNull PaymentMethod paymentMethod,
            /** Cash handed over, per currency. Empty means the exact total in the base currency. */
            @Size(max = 10) List<@Valid TenderRequest> tenders,
            /** Currency to give change in; the base currency when omitted. */
            String changeCurrency,
            /** Currency the KHQR code charges in (USD or KHR); the base currency when omitted. */
            String khqrCurrency,
            @Size(max = 255) String note,
            /** Who the sale is for (name or phone), optional. */
            @Size(max = 100) String customerName,
            /** The customer; null sells to the Walk-in Customer. */
            Long customerId,
            /** The held sale this cart was resumed from; completed (or locked until KHQR is paid) with the order. */
            Long heldSaleId,
            /** The total the cashier was shown; the sale is refused (409 PRICES_CHANGED) if it no longer matches. */
            @DecimalMin("0") BigDecimal expectedTotal) {
    }

    public record TenderRequest(@NotNull String currency,
                                @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal amount) {
    }

    public record RateDto(String currency, BigDecimal rate) {
    }

    /** One tax group: the net {@code base} after discount, and the tax, included in prices or added. */
    public record TaxDto(String name, BigDecimal rate, boolean included, BigDecimal base, BigDecimal tax) {
    }

    public record PartUsedDto(Long itemId, String itemName, int quantity) {
        static PartUsedDto from(OrderItemComponent c) {
            return new PartUsedDto(c.getItem().getId(), c.getItemName(), c.getQuantity());
        }
    }

    /** Something inside a sold package; {@code depth} > 0 means inside a nested package. */
    public record ContentDto(Long offeringId, OfferingType type, String name, String nameKm, BigDecimal quantity, String unit,
                             int depth) {
        static ContentDto from(OrderItemContent c) {
            return new ContentDto(c.getOffering().getId(), c.getOfferingType(), c.getName(), c.getNameKm(), c.getQuantity(), c.getUnit(),
                    c.getDepth());
        }
    }

    /** Units of order lines to take back. */
    public record ReturnLineRequest(@NotNull Long orderItemId, @Min(1) @Max(10_000) int quantity) {
    }

    public record ReturnRequest(@NotEmpty @Size(max = 200) List<@Valid ReturnLineRequest> lines,
                                @Size(max = 255) String reason) {
    }

    public record ReturnLineDto(Long orderItemId, String name, int quantity, BigDecimal total, BigDecimal tax) {
        static ReturnLineDto from(OrderReturnLine l) {
            return new ReturnLineDto(l.getOrderItem().getId(), l.getOrderItem().getName(), l.getQuantity(), l.getTotal(), l.getTax());
        }
    }

    /**
     * A return in the list of all returns: what was refunded (with the tax inside it), on which
     * order, for whom, and whether it took back everything that was left.
     */
    public record ReturnSummaryDto(Long id, String returnNumber, Long orderId, String orderNumber, Long customerId,
                                   boolean walkIn, String customerName, String branchName, BigDecimal total, BigDecimal tax,
                                   String reason, String items, String createdBy, Instant createdAt, boolean fullRefund) {
        static ReturnSummaryDto from(OrderReturn r) {
            Order o = r.getOrder();
            return new ReturnSummaryDto(r.getId(), r.getReturnNumber(), o.getId(), o.getOrderNumber(), o.getCustomer().getId(),
                    o.getCustomer().isDefaultCustomer(), o.getCustomerName(), o.getBranch().getName(), r.getTotal(), r.getTax(),
                    r.getReason(), r.getLines().stream().map(l -> l.getQuantity() + " × " + l.getOrderItem().getName())
                            .collect(java.util.stream.Collectors.joining(", ")),
                    r.getCreatedBy().getFullName(), r.getCreatedAt(), o.getStatus() == OrderStatus.REFUNDED
                            && o.getReturns().get(o.getReturns().size() - 1).getId().equals(r.getId()));
        }
    }

    /** A return: {@code total} refunded, {@code tax} the tax inside it. */
    public record ReturnDto(Long id, String returnNumber, String reason, BigDecimal total, BigDecimal tax, BigDecimal net,
                            String createdBy, Instant createdAt, List<ReturnLineDto> lines) {
        static ReturnDto from(OrderReturn r) {
            return new ReturnDto(r.getId(), r.getReturnNumber(), r.getReason(), r.getTotal(), r.getTax(), r.getNet(),
                    r.getCreatedBy().getFullName(), r.getCreatedAt(), r.getLines().stream().map(ReturnLineDto::from).toList());
        }
    }

    /**
     * A sold line. {@code taxRate} is the rate actually charged (0 when the line wasn't taxed);
     * {@code lineDiscount}, {@code lineNet}, {@code lineTax} and {@code linePaid} are its share of the order.
     */
    public record OrderItemDto(Long id, Long offeringId, OfferingType type, String name, String nameKm, String sku, String unit,
                               BigDecimal unitPrice, BigDecimal listPrice, boolean priceOverridden,
                               String priceOverrideReason, String priceOverrideBy, int quantity, BigDecimal hours,
                               Integer durationMinutes, String taxName, BigDecimal taxRate, boolean priceIncludesTax, BigDecimal lineTotal,
                               DiscountType itemDiscountType, BigDecimal itemDiscountValue, BigDecimal itemDiscount,
                               BigDecimal lineDiscount, BigDecimal lineNet, BigDecimal lineTax, BigDecimal linePaid,
                               int refundedQty, BigDecimal refundedAmount, BigDecimal refundedTax, String staffName,
                               List<PartUsedDto> partsUsed, List<ContentDto> contents) {
        static OrderItemDto from(OrderItem i) {
            return new OrderItemDto(i.getId(), i.getOffering().getId(), i.getOfferingType(), i.getName(), i.getNameKm(), i.getSku(),
                    i.getUnit(), i.getUnitPrice(), i.getListPrice(), i.isPriceOverridden(), i.getPriceOverrideReason(),
                    i.getPriceOverrideBy() == null ? null : i.getPriceOverrideBy().getFullName(), i.getQuantity(), i.getHours(), i.getDurationMinutes(),
                    i.getTaxName(), i.getTaxRate(), i.isPriceIncludesTax(), i.getLineTotal(),
                    i.getItemDiscountType(), i.getItemDiscountValue(), i.getItemDiscount(),
                    i.getLineDiscount(), i.getLineNet(), i.getLineTax(), i.getLinePaid(), i.getRefundedQty(),
                    i.getRefundedAmount(), i.getRefundedTax(), i.getStaffName(),
                    i.getComponents().stream().map(PartUsedDto::from).toList(),
                    i.getContents().stream().map(ContentDto::from).toList());
        }
    }

    public record OrderSummaryDto(Long id, String orderNumber, OrderStatus status, BigDecimal total, BigDecimal refundedTotal,
                                  String currency, Long customerId, boolean walkIn, String customerName, String cashierName,
                                  String branchName, Instant createdAt) {
        static OrderSummaryDto from(Order o) {
            return new OrderSummaryDto(o.getId(), o.getOrderNumber(), o.getStatus(), o.getTotal(), o.getRefundedTotal(), o.getCurrency(),
                    o.getCustomer().getId(), o.getCustomer().isDefaultCustomer(), o.getCustomerName(),
                    o.getCashier().getFullName(), o.getBranch().getName(), o.getCreatedAt());
        }
    }

    public record OrderDto(Long id, String orderNumber, OrderStatus status, BigDecimal subtotal,
                           DiscountType discountType, BigDecimal discountValue, BigDecimal discountAmount,
                           BigDecimal itemDiscount, BigDecimal taxAmount, BigDecimal taxIncluded, TaxMode taxMode, String taxLabel,
                           BigDecimal taxableAmount, BigDecimal nonTaxableAmount, BigDecimal total,
                           BigDecimal refundedTotal, BigDecimal refundedTax, String currency, String note, Long customerId, String customerCode,
                           boolean walkIn, String customerName, String customerPhone, String customerTaxId, String cashierName,
                           Long branchId, String branchName, String storageName,
                           Instant createdAt, Instant completedAt, List<OrderItemDto> items,
                           List<PaymentDto> payments, List<RateDto> rates, List<TaxDto> taxes, List<ReturnDto> returns) {
        public static OrderDto from(Order o) {
            return new OrderDto(o.getId(), o.getOrderNumber(), o.getStatus(), o.getSubtotal(), o.getDiscountType(),
                    o.getDiscountValue(), o.getDiscountAmount(), o.getItemDiscount(), o.getTaxAmount(), o.getTaxIncluded(), o.getTaxMode(), o.getTaxLabel(),
                    o.getTaxableAmount(), o.getNonTaxableAmount(), o.getTotal(), o.getRefundedTotal(), o.getRefundedTax(), o.getCurrency(),
                    o.getNote(), o.getCustomer().getId(), o.getCustomer().getCode(), o.getCustomer().isDefaultCustomer(),
                    o.getCustomerName(), o.getCustomer().isDefaultCustomer() ? null : o.getCustomer().getPhone(),
                    o.getCustomer().isDefaultCustomer() ? null : o.getCustomer().getTaxId(), o.getCashier().getFullName(), o.getBranch().getId(), o.getBranch().getName(),
                    o.getStorage().getName(), o.getCreatedAt(), o.getCompletedAt(),
                    o.getItems().stream().map(OrderItemDto::from).toList(),
                    o.getPayments().stream().map(PaymentDto::from).toList(),
                    o.getRates().stream().map(r -> new RateDto(r.getCurrency(), r.getRate())).toList(),
                    o.getTaxes().stream().map(t -> new TaxDto(t.getName(), t.getRate(), t.isIncluded(), t.getBase(),
                            t.getTax())).toList(),
                    o.getReturns().stream().map(ReturnDto::from).toList());
        }
    }
}
