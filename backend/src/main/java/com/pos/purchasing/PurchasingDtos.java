package com.pos.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PurchasingDtos {

    private PurchasingDtos() {
    }

    // --- vendors

    public record VendorRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 150) String name,
                                @Size(max = 100) String contactName, @Size(max = 50) String phone,
                                @Email @Size(max = 100) String email, @Size(max = 255) String address,
                                @Size(max = 50) String taxId, @Min(0) @Max(365) int paymentTermsDays,
                                @Size(max = 500) String note, Boolean active) {
    }

    /** {@code balance} is what is owed on unpaid invoices; {@code openOrders} counts orders still to arrive. */
    public record VendorDto(Long id, String code, String name, String contactName, String phone, String email,
                            String address, String taxId, int paymentTermsDays, String note, boolean active,
                            BigDecimal balance, long openOrders) {
    }

    // --- purchase orders

    /** {@code unitId} null means the entry's base unit; {@code unitCost} is per that unit. */
    public record LineRequest(@NotNull Long offeringId, Long unitId, @Min(1) @Max(1_000_000) int quantity,
                              @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal unitCost) {
    }

    public record OrderRequest(@NotNull Long vendorId, @NotNull Long storageId, LocalDate expectedDate,
                               @Size(max = 60) String vendorRef, @Size(max = 500) String note,
                               @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    public record LineDto(Long id, Long offeringId, String name, String sku, Long unitId, String unit, int factor,
                          int quantity, int receivedQty, int remaining, BigDecimal unitCost, BigDecimal lineTotal) {
    }

    public record ReceiptLineDto(Long lineId, String name, String unit, int quantity, int baseQuantity, BigDecimal unitCost) {
    }

    public record ReceiptDto(Long id, String receiptNumber, String storageName, String deliveryRef, String note,
                             String receivedBy, Instant receivedAt, List<ReceiptLineDto> lines) {
    }

    public record OrderSummaryDto(Long id, String poNumber, PurchaseStatus status, Long vendorId, String vendorName,
                                  String branchName, BigDecimal total, LocalDate expectedDate, Instant createdAt,
                                  int lines, int linesOpen) {
    }

    public record OrderDto(Long id, String poNumber, PurchaseStatus status, Long vendorId, String vendorName,
                           Long branchId, String branchName, Long storageId, String storageName, LocalDate expectedDate,
                           String vendorRef, String note, BigDecimal total, BigDecimal receivedValue, String createdBy,
                           Instant createdAt, Instant orderedAt, Instant closedAt, List<LineDto> lines,
                           List<ReceiptDto> receipts, List<InvoiceDto> invoices) {
    }

    // --- receiving

    public record ReceiveLine(@NotNull Long lineId, @Min(1) @Max(1_000_000) int quantity) {
    }

    /** @param updateCost also set each entry's catalog cost to this purchase price (per base unit) */
    public record ReceiveRequest(@NotEmpty @Size(max = 200) List<@Valid ReceiveLine> lines, @Size(max = 60) String deliveryRef,
                                 @Size(max = 255) String note, boolean updateCost) {
    }

    // --- invoices and payments

    public record InvoiceRequest(@NotNull Long vendorId, Long purchaseOrderId, @NotBlank @Size(max = 60) String invoiceNumber,
                                 @NotNull LocalDate invoiceDate, LocalDate dueDate,
                                 @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal subtotal,
                                 @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal tax,
                                 @Size(max = 255) String note) {
    }

    public record PaymentRequest(@NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                 @NotNull VendorPaymentMethod method, @NotNull LocalDate paidOn,
                                 @Size(max = 100) String reference, @Size(max = 255) String note) {
    }

    public record PaymentDto(Long id, BigDecimal amount, VendorPaymentMethod method, LocalDate paidOn, String reference,
                             String note, String createdBy, Instant createdAt) {
    }

    public record InvoiceDto(Long id, Long vendorId, String vendorName, Long purchaseOrderId, String poNumber,
                             String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate, BigDecimal subtotal,
                             BigDecimal tax, BigDecimal total, BigDecimal paid, BigDecimal balance, InvoiceStatus status,
                             boolean overdue, String note, String createdBy, Instant createdAt, List<PaymentDto> payments) {
    }

    /** Owed to vendors in total, the overdue part, and orders still to arrive. */
    public record PayablesSummary(BigDecimal owed, BigDecimal overdue, long unpaidInvoices, long openOrders) {
    }
}
