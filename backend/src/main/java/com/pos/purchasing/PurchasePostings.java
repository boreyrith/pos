package com.pos.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.accounting.Account;
import com.pos.accounting.AccountingSettings;
import com.pos.accounting.JournalSource;
import com.pos.accounting.Ledger;
import com.pos.accounting.SystemAccount;
import com.pos.common.Money;
import com.pos.settings.SettingsService;

/**
 * The journal entries for purchasing's documents, made through the {@link Ledger} in the caller's
 * transaction, like {@code accounting.Postings} does for sales and stock. Deliveries come in at
 * catalog cost (any difference from the purchase price goes to Purchase price differences), invoices
 * are owed to the vendor, and payments take money out of the account they were paid from.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PurchasePostings {

    private final Ledger ledger;
    private final SettingsService settings;

    public PurchasePostings(Ledger ledger, SettingsService settings) {
        this.ledger = ledger;
        this.settings = settings;
    }

    private LocalDate today() {
        return LocalDate.now(settings.current().timezone());
    }

    /**
     * A delivery: stock in at catalog cost; owed for it (until the vendor's invoice comes) at the
     * purchase price; the difference to Purchase price differences. Call before any cost update.
     */
    public void goodsReceipt(PurchaseOrder po, GoodsReceipt receipt, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started()) {
            return;
        }
        Map<Long, Integer> stock = new TreeMap<>();
        BigDecimal owed = BigDecimal.ZERO;
        for (GoodsReceiptLine rl : receipt.getLines()) {
            stock.merge(rl.getLine().getOffering().getId(), rl.getBaseQuantity(), Integer::sum);
            owed = owed.add(Money.round(rl.getUnitCost().multiply(BigDecimal.valueOf(rl.getQuantity()))));
        }
        BigDecimal atCost = ledger.stockValue(stock);
        Ledger.Draft d = new Ledger.Draft(JournalSource.GOODS_RECEIPT, ledger.openDate(s, today()),
                "Delivery " + receipt.getReceiptNumber() + " from " + po.getVendor().getName() + " (" + po.getPoNumber() + ")")
                .branch(po.getBranch().getId())
                .source(po.getId(), "RECEIPT:" + receipt.getId(), receipt.getReceiptNumber())
                .debit(ledger.account(SystemAccount.INVENTORY), atCost, null)
                .debit(ledger.account(SystemAccount.PRICE_DIFFERENCES), owed.subtract(atCost), null)
                .party(po.getVendor().getId(), null)
                .credit(ledger.account(SystemAccount.GOODS_NOT_INVOICED), owed, po.getPoNumber());
        ledger.post(d, userId);
    }

    /**
     * A vendor invoice: owed to the vendor (Accounts payable). Against a purchase order it clears
     * Goods received not invoiced; otherwise it is an expense (the account chosen). Its VAT is input
     * VAT. Voiding posts the same with sides swapped.
     */
    public void vendorInvoice(VendorInvoice i, boolean voided, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started()) {
            return;
        }
        PurchaseOrder po = i.getPurchaseOrder();
        Account against = po != null ? ledger.account(SystemAccount.GOODS_NOT_INVOICED)
                : i.getExpenseAccount() != null ? i.getExpenseAccount() : ledger.account(SystemAccount.GENERAL_EXPENSES);
        boolean dr = !voided;
        String what = i.getVendor().getName() + " invoice " + i.getInvoiceNumber();
        Ledger.Draft d = new Ledger.Draft(voided ? JournalSource.VENDOR_INVOICE_VOID : JournalSource.VENDOR_INVOICE,
                ledger.openDate(s, voided ? today() : i.getInvoiceDate()), (voided ? "Voided " : "") + what)
                .branch(po == null ? null : po.getBranch().getId())
                .source(i.getId(), (voided ? "INVOICE_VOID:" : "INVOICE:") + i.getId(), i.getInvoiceNumber())
                .party(i.getVendor().getId(), null)
                .add(against, i.getSubtotal(), dr, po == null ? null : po.getPoNumber())
                .add(ledger.account(SystemAccount.VAT_INPUT), i.getTax(), dr, null)
                .add(ledger.account(SystemAccount.PAYABLES), i.getTotal(), !dr, null);
        ledger.post(d, userId);
    }

    /**
     * The cash or bank account a vendor payment comes from: the one chosen, else by method (cash:
     * the branch's drawer; KHQR: Bakong; anything else: the bank). Null while the books aren't started
     * and nothing was chosen.
     */
    public Account vendorPaymentAccount(VendorPaymentMethod method, Account chosen, Long branchId) {
        if (chosen != null) {
            return chosen;
        }
        if (!ledger.settings().started()) {
            return null;
        }
        return switch (method) {
            case CASH -> ledger.cash(branchId);
            case KHQR -> ledger.account(SystemAccount.KHQR);
            default -> ledger.account(SystemAccount.BANK);
        };
    }

    /** A vendor payment: less owed, money out of the account it was paid from. */
    public void vendorPayment(VendorPayment p, Long branchId, Long userId) {
        AccountingSettings s = ledger.settings();
        if (!s.started() || p.getMoneyAccount() == null) {
            return;
        }
        VendorInvoice i = p.getInvoice();
        Ledger.Draft d = new Ledger.Draft(JournalSource.VENDOR_PAYMENT, ledger.openDate(s, p.getPaidOn()),
                "Paid " + i.getVendor().getName() + " invoice " + i.getInvoiceNumber())
                .branch(branchId)
                .source(i.getId(), "VENDOR_PAYMENT:" + p.getId(), p.getReference() != null ? p.getReference() : i.getInvoiceNumber())
                .party(i.getVendor().getId(), null)
                .debit(ledger.account(SystemAccount.PAYABLES), p.getAmount(), null)
                .party(null, null)
                .credit(p.getMoneyAccount(), p.getAmount(), p.getMethod().name());
        ledger.post(d, userId);
    }
}
