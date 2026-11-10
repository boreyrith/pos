package com.pos.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.UserRepository;
import com.pos.branch.Branch;
import com.pos.branch.BranchRepository;
import com.pos.common.ApiException;
import com.pos.common.Money;
import com.pos.customer.CustomerRepository;
import com.pos.vendor.VendorRepository;

/**
 * The one place journal entries are made. Every entry is checked to balance (debits = credits, to
 * the cent), uses active accounts and falls on an open date. Automatic postings (sales, deliveries,
 * invoices...) go through {@link Postings}; they are skipped until the books are started.
 */
@Service
public class Ledger {

    /** One line of an entry being built. */
    public record DraftLine(Account account, BigDecimal debit, BigDecimal credit, String memo, Long vendorId, Long customerId) {
    }

    /** An entry being built: add debits and credits, then {@link Ledger#post}. Zero amounts are dropped. */
    public static final class Draft {
        final JournalSource source;
        final String memo;
        LocalDate date;
        Long branchId;
        Long sourceId;
        String sourceKey;
        String reference;
        Long vendorId;
        Long customerId;
        JournalEntry reversalOf;
        final List<DraftLine> lines = new ArrayList<>();

        public Draft(JournalSource source, LocalDate date, String memo) {
            this.source = source;
            this.date = date;
            this.memo = memo;
        }

        public Draft branch(Long id) {
            branchId = id;
            return this;
        }

        public Draft source(Long id, String key, String ref) {
            sourceId = id;
            sourceKey = key;
            reference = ref;
            return this;
        }

        /** The vendor or customer for the lines added after this. */
        public Draft party(Long vendor, Long customer) {
            vendorId = vendor;
            customerId = customer;
            return this;
        }

        public Draft debit(Account account, BigDecimal amount, String lineMemo) {
            return add(account, amount, true, lineMemo);
        }

        public Draft credit(Account account, BigDecimal amount, String lineMemo) {
            return add(account, amount, false, lineMemo);
        }

        /** A positive amount on its side, a negative one on the other side. */
        public Draft add(Account account, BigDecimal amount, boolean debit, String lineMemo) {
            BigDecimal a = amount == null ? BigDecimal.ZERO : Money.round(amount);
            if (a.signum() != 0) {
                boolean d = debit == (a.signum() > 0);
                lines.add(new DraftLine(account, d ? a.abs() : BigDecimal.ZERO, d ? BigDecimal.ZERO : a.abs(), lineMemo, vendorId, customerId));
            }
            return this;
        }

        public Draft reverses(JournalEntry original) {
            reversalOf = original;
            return this;
        }

        public List<DraftLine> lines() {
            return lines;
        }
    }

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final AccountingSettingsRepository settings;
    private final BranchRepository branches;
    private final UserRepository users;
    private final VendorRepository vendors;
    private final CustomerRepository customers;
    private final JdbcClient jdbc;

    public Ledger(JournalEntryRepository entries, AccountRepository accounts, AccountingSettingsRepository settings,
                  BranchRepository branches, UserRepository users, VendorRepository vendors, CustomerRepository customers,
                  JdbcClient jdbc) {
        this.entries = entries;
        this.accounts = accounts;
        this.settings = settings;
        this.branches = branches;
        this.users = users;
        this.vendors = vendors;
        this.customers = customers;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public AccountingSettings settings() {
        return settings.findById(1L).orElseThrow();
    }

    /** A system account by key. */
    public Account account(String systemKey) {
        return accounts.findBySystemKey(systemKey)
                .orElseThrow(() -> new IllegalStateException("The " + systemKey + " account is missing"));
    }

    /** An active account the user picked, of a type that fits. */
    public Account pick(Long id, java.util.function.Predicate<AccountType> fits, String what) {
        Account a = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
        if (!a.isActive()) {
            throw ApiException.badRequest(a.label() + " is not active");
        }
        if (!fits.test(a.getType())) {
            throw ApiException.badRequest(a.label() + " is not " + what);
        }
        return a;
    }

    /** For an expense: an expense, cost of sales or fixed asset account. */
    public Account expenseAccount(Long id) {
        return pick(id, AccountType::expense, "an expense account");
    }

    /** For money in or out: a cash or bank account. */
    public Account moneyAccount(Long id) {
        return pick(id, AccountType::money, "a cash or bank account");
    }

    /** The branch's cash drawer account, created the first time a new branch needs one. */
    public Account cash(Long branchId) {
        return accounts.findBySystemKey(SystemAccount.cash(branchId)).orElseGet(() -> {
            Branch branch = branches.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch"));
            Account a = new Account();
            a.setCode(freeCode(branchId));
            a.setName("Cash - " + branch.getName());
            a.setType(AccountType.CASH);
            a.setSystemKey(SystemAccount.cash(branchId));
            a.setBranch(branch);
            a.setDescription("Cash drawer of this branch");
            return accounts.saveAndFlush(a);
        });
    }

    private String freeCode(Long branchId) {
        for (String code : List.of(String.format("10%02d", branchId), "10" + branchId, "C" + branchId)) {
            if (code.length() <= 10 && !accounts.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No free account code for the cash of branch " + branchId);
    }

    /**
     * Where an automatic posting dated {@code date} lands: on that date, or on the first open date if
     * that day is before the start of the books or in a closed period.
     */
    public LocalDate openDate(AccountingSettings s, LocalDate date) {
        LocalDate first = s.firstOpenDate();
        return date.isBefore(first) ? first : date;
    }

    /** Refuses a date in a closed period or before the books start (entries the accountant makes). */
    public void requireOpen(AccountingSettings s, LocalDate date) {
        if (!s.started()) {
            throw ApiException.conflict("Start the books first (Accounting > Setup)");
        }
        if (date.isBefore(s.getStartDate())) {
            throw ApiException.badRequest("The books start on " + s.getStartDate() + "; pick that date or later");
        }
        if (s.getLockDate() != null && !date.isAfter(s.getLockDate())) {
            throw ApiException.badRequest("The books are closed up to " + s.getLockDate() + "; pick a later date");
        }
    }

    /**
     * Saves the entry. Returns empty if every amount was zero. A posting whose source key was
     * already posted is not posted again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<JournalEntry> post(Draft d, Long userId) {
        if (d.lines.isEmpty() || (d.sourceKey != null && entries.existsBySourceKey(d.sourceKey))) {
            return Optional.empty();
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (DraftLine l : d.lines) {
            debits = debits.add(l.debit());
            credits = credits.add(l.credit());
        }
        if (debits.compareTo(credits) != 0) {
            throw ApiException.badRequest("The entry doesn't balance: debits " + debits.toPlainString() + ", credits "
                    + credits.toPlainString());
        }
        JournalEntry e = new JournalEntry();
        e.setEntryDate(d.date);
        e.setBranch(d.branchId == null ? null : branches.getReferenceById(d.branchId));
        e.setSource(d.source);
        e.setSourceId(d.sourceId);
        e.setSourceKey(d.sourceKey);
        e.setReference(d.reference);
        e.setMemo(d.memo.length() > 255 ? d.memo.substring(0, 252) + "..." : d.memo);
        e.setTotal(debits);
        e.setReversalOf(d.reversalOf);
        e.setCreatedBy(userId == null ? null : users.getReferenceById(userId));
        int n = 0;
        for (DraftLine l : d.lines) {
            JournalLine line = new JournalLine();
            line.setEntry(e);
            line.setLineNo(++n);
            line.setAccount(l.account());
            line.setDebit(l.debit());
            line.setCredit(l.credit());
            line.setMemo(l.memo());
            line.setVendor(l.vendorId() == null ? null : vendors.getReferenceById(l.vendorId()));
            line.setCustomer(l.customerId() == null ? null : customers.getReferenceById(l.customerId()));
            e.getLines().add(line);
        }
        entries.saveAndFlush(e);
        e.setEntryNumber(String.format("JE-%06d", e.getId()));
        return Optional.of(e);
    }

    /** An entry that cancels {@code original}: the same lines, sides swapped. */
    public Draft reversal(JournalEntry original, LocalDate date, String memo) {
        Draft d = new Draft(JournalSource.REVERSAL, date, memo).source(original.getId(), null, original.getEntryNumber())
                .branch(original.getBranch() == null ? null : original.getBranch().getId()).reverses(original);
        for (JournalLine l : original.getLines()) {
            d.party(l.getVendor() == null ? null : l.getVendor().getId(), l.getCustomer() == null ? null : l.getCustomer().getId());
            d.add(l.getAccount(), l.getDebit().signum() > 0 ? l.getDebit() : l.getCredit(), l.getDebit().signum() == 0, l.getMemo());
        }
        return d;
    }

    // ---------------------------------------------------------------------------------- stock value

    /** Stock at today's catalog cost: quantity x cost per entry, each rounded to cents. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BigDecimal stockValue(Map<Long, Integer> quantities) {
        if (quantities.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<Long, BigDecimal> costs = new HashMap<>();
        jdbc.sql("SELECT id, cost FROM offerings WHERE id IN (:ids)").param("ids", quantities.keySet())
                .query((rs, i) -> costs.put(rs.getLong("id"), rs.getBigDecimal("cost"))).list();
        BigDecimal total = BigDecimal.ZERO;
        for (var q : quantities.entrySet()) {
            BigDecimal cost = costs.get(q.getKey());
            if (cost != null) {
                total = total.add(Money.round(cost.multiply(BigDecimal.valueOf(q.getValue()))));
            }
        }
        return total;
    }

    /** Units of an entry on hand in every storage. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int onHand(Long offeringId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM stock_levels WHERE offering_id = :id").param("id", offeringId)
                .query(Integer.class).single();
    }

    /** Stock valuation now, every storage: what the Inventory account should hold. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BigDecimal stockValueNow() {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(ROUND(s.quantity * o.cost, 2)), 0) FROM (
                            SELECT offering_id, SUM(quantity) AS quantity FROM stock_levels GROUP BY offering_id
                        ) s JOIN offerings o ON o.id = s.offering_id
                        """)
                .query(BigDecimal.class).single();
    }
}
