package com.pos.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.Offering;
import com.pos.common.Money;
import com.pos.inventory.StockAdjustment;
import com.pos.inventory.StockBookkeeping;
import com.pos.payment.Payment;
import com.pos.payment.PaymentMethod;
import com.pos.payment.PaymentStatus;
import com.pos.sales.Order;
import com.pos.sales.OrderReturn;
import com.pos.sales.SaleBookkeeping;
import com.pos.settings.SettingsService;

/**
 * The journal entries sales and stock make as they work (purchasing makes its own, in
 * {@code purchasing.PurchasePostings}). Each runs in the caller's
 * transaction, so a sale and its entry are saved together or not at all. Nothing posts until the
 * books are started; an event dated before the start or in a closed period posts on the first open day.
 * <p>Stock is valued at the catalog cost, so the Inventory account follows the stock valuation
 * report: sales and returns move stock at cost, and a cost change revalues what is on hand.
 * Transfers between branches don't post: stock stays the company's.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class Postings implements SaleBookkeeping, StockBookkeeping {

    private final Ledger ledger;
    private final SettingsService settings;

    public Postings(Ledger ledger, SettingsService settings) {
        this.ledger = ledger;
        this.settings = settings;
    }

    private LocalDate today() {
        return LocalDate.now(settings.current().timezone());
    }

    /**
     * A completed sale: money in (the branch's cash, or Bakong for KHQR), sales and VAT out; the
     * stock it took moves from Inventory to Cost of goods sold. {@code stock} is units per entry.
     */
    public void sale(Order order, Map<Long, Integer> stock, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started()) {
            return;
        }
        LocalDate day = order.getCompletedAt() == null ? today()
                : order.getCompletedAt().atZone(settings.current().timezone()).toLocalDate();
        boolean khqr = order.getPayments().stream()
                .anyMatch(p -> p.getMethod() == PaymentMethod.KHQR && p.getStatus() == PaymentStatus.PAID);
        Long customer = order.getCustomer() == null ? null : order.getCustomer().getId();
        BigDecimal cost = ledger.stockValue(stock);
        Ledger.Draft d = new Ledger.Draft(JournalSource.SALE, ledger.openDate(s, day), "Sale " + order.getOrderNumber())
                .branch(order.getBranch().getId())
                .source(order.getId(), "SALE:" + order.getId(), order.getOrderNumber())
                .party(null, customer)
                .debit(khqr ? ledger.account(SystemAccount.KHQR) : ledger.cash(order.getBranch().getId()), order.getTotal(),
                        khqr ? "KHQR" : "Cash")
                .credit(ledger.account(SystemAccount.SALES), order.getTotal().subtract(order.getTaxAmount()), null)
                .credit(ledger.account(SystemAccount.VAT_OUTPUT), order.getTaxAmount(), order.getTaxLabel())
                .party(null, null)
                .debit(ledger.account(SystemAccount.COST_OF_SALES), cost, null)
                .credit(ledger.account(SystemAccount.INVENTORY), cost, null);
        ledger.post(d, userId);
    }

    /**
     * A return: the refund goes out the way the sale was paid; sales and VAT are reduced by what
     * was refunded; the stock that came back returns to Inventory at cost.
     */
    public void saleReturn(Order order, OrderReturn ret, Map<Long, Integer> stockBack, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started()) {
            return;
        }
        boolean khqr = order.getPayments().stream().map(Payment::getMethod).anyMatch(m -> m == PaymentMethod.KHQR);
        Long customer = order.getCustomer() == null ? null : order.getCustomer().getId();
        BigDecimal cost = ledger.stockValue(stockBack);
        Ledger.Draft d = new Ledger.Draft(JournalSource.SALE_RETURN, ledger.openDate(s, today()),
                "Return " + ret.getReturnNumber() + " on " + order.getOrderNumber())
                .branch(order.getBranch().getId())
                .source(order.getId(), "RETURN:" + ret.getId(), ret.getReturnNumber())
                .party(null, customer)
                .debit(ledger.account(SystemAccount.SALES), ret.getNet(), null)
                .debit(ledger.account(SystemAccount.VAT_OUTPUT), ret.getTax(), order.getTaxLabel())
                .credit(khqr ? ledger.account(SystemAccount.KHQR) : ledger.cash(order.getBranch().getId()), ret.getTotal(), "Refund")
                .party(null, null)
                .debit(ledger.account(SystemAccount.INVENTORY), cost, null)
                .credit(ledger.account(SystemAccount.COST_OF_SALES), cost, null);
        ledger.post(d, userId);
    }

    /** A catalog cost change: what is on hand is revalued to the new cost. */
    public void costChanged(Offering o, BigDecimal before, BigDecimal after, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started() || !o.getType().tracksStock()) {
            return;
        }
        BigDecimal was = before == null ? BigDecimal.ZERO : before;
        BigDecimal now = after == null ? BigDecimal.ZERO : after;
        int qty = ledger.onHand(o.getId());
        BigDecimal change = Money.round(now.multiply(BigDecimal.valueOf(qty))).subtract(Money.round(was.multiply(BigDecimal.valueOf(qty))));
        Ledger.Draft d = new Ledger.Draft(JournalSource.REVALUATION, ledger.openDate(s, today()),
                "Cost of " + o.getName() + " " + was.setScale(2, RoundingMode.HALF_UP).toPlainString() + " -> "
                        + now.setScale(2, RoundingMode.HALF_UP).toPlainString() + " on " + qty + " on hand")
                .source(o.getId(), null, o.getSku())
                .debit(ledger.account(SystemAccount.INVENTORY), change, null)
                .credit(ledger.account(SystemAccount.REVALUATION), change, null);
        ledger.post(d, userId);
    }

    /** A stock adjustment or count: the value of what changed, against Stock adjustments. */
    public void stockAdjustment(StockAdjustment a, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started()) {
            return;
        }
        Ledger.Draft d = new Ledger.Draft(JournalSource.STOCK_ADJUSTMENT, ledger.openDate(s, today()),
                "Stock " + (a.getKind() == com.pos.inventory.AdjustmentKind.COUNT ? "count " : "adjustment ") + a.getAdjustmentNumber()
                        + " at " + a.getStorage().getName())
                .branch(a.getStorage().getBranch().getId())
                .source(a.getId(), "ADJUSTMENT:" + a.getId(), a.getAdjustmentNumber())
                .debit(ledger.account(SystemAccount.INVENTORY), a.getValueChange(), null)
                .credit(ledger.account(SystemAccount.STOCK_ADJUSTMENTS), a.getValueChange(), null);
        ledger.post(d, userId);
    }

    /** Stock entered with a new catalog entry: brought into the books as an opening balance. */
    public void openingStock(Offering o, Long branchId, int qty, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started()) {
            return;
        }
        BigDecimal value = ledger.stockValue(Map.of(o.getId(), qty));
        Ledger.Draft d = new Ledger.Draft(JournalSource.OPENING_STOCK, ledger.openDate(s, today()),
                "Opening stock of " + o.getName() + ": " + qty)
                .branch(branchId)
                .source(o.getId(), "OPENING_STOCK:" + o.getId(), o.getSku())
                .debit(ledger.account(SystemAccount.INVENTORY), value, null)
                .credit(ledger.account(SystemAccount.OPENING), value, null);
        ledger.post(d, userId);
    }
}
