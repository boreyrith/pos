package com.pos.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.pos.catalog.OfferingType;

public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * Headline figures for a period.
     *
     * @param sales        what customers paid, tax included, minus refunds made in the period
     * @param netSales     the same without tax
     * @param averageOrder what an order came to (tax included) before any later refunds
     * @param cost         cost of what was sold and kept
     * @param margin       profit as % of net sales; null without sales
     */
    public record Kpis(long orders, long items, BigDecimal sales, BigDecimal netSales, BigDecimal tax, BigDecimal discounts,
                       long returns, BigDecimal refunds, BigDecimal cost, BigDecimal profit, BigDecimal margin, BigDecimal averageOrder) {
    }

    public record DailySales(LocalDate date, BigDecimal sales, long orders, BigDecimal profit) {
    }

    /** A best-selling entry: units kept (sold less returned), net sales without tax, and profit. */
    public record TopSeller(Long offeringId, OfferingType type, String name, long quantity, BigDecimal netSales, BigDecimal profit) {
    }

    /** Money received by method, by when it was paid (later refunds don't remove it). */
    public record PaymentBreakdown(String method, BigDecimal amount, long count) {
    }

    /** Cash per currency for the drawer: handed over by customers, given back as change, and kept. */
    public record CashByCurrency(String currency, BigDecimal received, BigDecimal changeGiven, BigDecimal net) {
    }

    public record LowStockItem(Long offeringId, String name, String unit, long stock, int threshold) {
    }

    public record TopCustomer(Long customerId, String name, long orders, BigDecimal spent) {
    }

    /** What is owed to vendors; only for people who can see purchasing. */
    public record Payables(BigDecimal owed, BigDecimal overdue, long openOrders) {
    }

    /**
     * @param previous   the same number of days just before {@code from}, for comparison
     * @param refundsPaid refunds made in the period (tax included)
     */
    public record Dashboard(String currency, LocalDate from, LocalDate to, Kpis today, Kpis period, Kpis previous,
                            List<DailySales> daily, List<TopSeller> topSellers, List<PaymentBreakdown> paymentMethods,
                            BigDecimal refundsPaid, List<CashByCurrency> cashByCurrency, long lowStock, List<LowStockItem> lowStockItems,
                            long pendingOrders, long incomingTransfers, long newCustomers, List<TopCustomer> topCustomers,
                            Payables payables) {
    }
}
