package com.pos.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.accounting.AccountingDtos.AccountDto;
import com.pos.accounting.AccountingDtos.AccountOption;
import com.pos.accounting.AccountingDtos.AccountRequest;
import com.pos.accounting.AccountingDtos.EntryDto;
import com.pos.accounting.AccountingDtos.JournalLineRequest;
import com.pos.accounting.AccountingDtos.JournalRequest;
import com.pos.accounting.AccountingDtos.LedgerDto;
import com.pos.accounting.AccountingDtos.LedgerLine;
import com.pos.accounting.AccountingDtos.LineDto;
import com.pos.accounting.AccountingDtos.LockRequest;
import com.pos.accounting.AccountingDtos.OpeningLine;
import com.pos.accounting.AccountingDtos.OtherDto;
import com.pos.accounting.AccountingDtos.OtherRequest;
import com.pos.accounting.AccountingDtos.OverviewDto;
import com.pos.accounting.AccountingDtos.ReverseRequest;
import com.pos.accounting.AccountingDtos.SettingsDto;
import com.pos.accounting.AccountingDtos.SetupRequest;
import com.pos.accounting.AccountingDtos.SuggestedLine;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.branch.BranchRepository;
import com.pos.common.ApiException;
import com.pos.customer.CustomerRepository;
import com.pos.inventory.BranchContext;
import com.pos.settings.SettingsService;
import com.pos.vendor.VendorRepository;

/**
 * The Accounting module: the chart of accounts, the journal and ledgers, starting and closing the
 * books, and other income and expenses. Entries are made through {@link Ledger}; automatic ones
 * come from {@link Postings}.
 */
@Service
public class AccountingService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final OtherTransactionRepository others;
    private final AccountingSettingsRepository settingsRepo;
    private final Ledger ledger;
    private final BranchContext branchContext;
    private final BranchRepository branches;
    private final UserRepository users;
    private final VendorRepository vendors;
    private final CustomerRepository customers;
    private final AuditService audit;
    private final SettingsService settings;
    private final JdbcClient jdbc;
    private final OpenPurchases purchases;

    public AccountingService(AccountRepository accounts, JournalEntryRepository entries, OtherTransactionRepository others,
                             AccountingSettingsRepository settingsRepo, Ledger ledger, BranchContext branchContext,
                             BranchRepository branches, UserRepository users, VendorRepository vendors, CustomerRepository customers,
                             AuditService audit, SettingsService settings, JdbcClient jdbc,
                             OpenPurchases purchases) {
        this.accounts = accounts;
        this.entries = entries;
        this.others = others;
        this.settingsRepo = settingsRepo;
        this.ledger = ledger;
        this.branchContext = branchContext;
        this.branches = branches;
        this.users = users;
        this.vendors = vendors;
        this.customers = customers;
        this.audit = audit;
        this.settings = settings;
        this.jdbc = jdbc;
        this.purchases = purchases;
    }

    private LocalDate today() {
        return LocalDate.now(settings.current().timezone());
    }

    // ------------------------------------------------------------------------------------ accounts

    /** Every account with its balance at the end of {@code asOf}. */
    @Transactional(readOnly = true)
    public List<AccountDto> accounts(LocalDate asOf, Long branchId) {
        Map<Long, BigDecimal[]> sums = sums(null, asOf, branchId);
        return accounts.findAllByOrderByCodeAsc().stream().map(a -> AccountDto.from(a, balance(a, sums.get(a.getId())))).toList();
    }

    /**
     * Active accounts for a picker: money (cash and bank), expense, income, or every account
     * ("all", for journal entries).
     */
    @Transactional(readOnly = true)
    public List<AccountOption> options(String purpose) {
        Predicate<AccountType> fits = switch (purpose == null ? "all" : purpose) {
            case "money" -> AccountType::money;
            case "expense" -> AccountType::expense;
            case "income" -> AccountType::income;
            case "all" -> t -> true;
            default -> throw ApiException.badRequest("Unknown account list " + purpose);
        };
        return accounts.findAllByOrderByCodeAsc().stream().filter(a -> a.isActive() && fits.test(a.getType()))
                .map(AccountOption::from).toList();
    }

    @Transactional
    public AccountDto createAccount(AccountRequest req) {
        String code = req.code().trim();
        if (accounts.existsByCode(code)) {
            throw ApiException.conflict("Account code " + code + " is already used");
        }
        Account a = new Account();
        a.setCode(code);
        a.setName(req.name().trim());
        a.setType(req.type());
        a.setDescription(blank(req.description()));
        a.setActive(req.active() == null || req.active());
        accounts.saveAndFlush(a);
        audit.log(AuditAction.ACCOUNT_CREATED, "Account", a.getId(), "Added account " + a.label() + " (" + a.getType().label() + ")");
        return AccountDto.from(a, BigDecimal.ZERO);
    }

    /** System accounts keep their type and stay active; an account with entries keeps its type. */
    @Transactional
    public AccountDto updateAccount(Long id, AccountRequest req) {
        Account a = accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
        String code = req.code().trim();
        if (accounts.existsByCodeAndIdNot(code, id)) {
            throw ApiException.conflict("Account code " + code + " is already used");
        }
        boolean active = req.active() == null || req.active();
        if (a.getType() != req.type()) {
            if (a.isSystem()) {
                throw ApiException.badRequest(a.label() + " is used by automatic postings, so its type can't change");
            }
            if (used(a.getId())) {
                throw ApiException.badRequest(a.label() + " has entries, so its type can't change");
            }
        }
        if (!active && a.isSystem()) {
            throw ApiException.badRequest(a.label() + " is used by automatic postings and can't be turned off");
        }
        Changes changes = new Changes()
                .add("Code", a.getCode(), code)
                .add("Name", a.getName(), req.name().trim())
                .add("Type", a.getType().label(), req.type().label())
                .add("Description", a.getDescription(), blank(req.description()))
                .add("Active", a.isActive() ? "yes" : "no", active ? "yes" : "no");
        a.setCode(code);
        a.setName(req.name().trim());
        a.setType(req.type());
        a.setDescription(blank(req.description()));
        a.setActive(active);
        if (!changes.isEmpty()) {
            audit.log(AuditAction.ACCOUNT_UPDATED, "Account", a.getId(), "Updated account " + a.label() + ": " + changes.describe(), changes);
        }
        Map<Long, BigDecimal[]> sums = sums(null, null, null);
        return AccountDto.from(a, balance(a, sums.get(a.getId())));
    }

    private boolean used(Long accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM journal_lines WHERE account_id = :id)").param("id", accountId)
                .query(Boolean.class).single();
    }

    // -------------------------------------------------------------------------------------- ledger

    /** At most this many lines are listed; the totals always cover the whole period. */
    private static final int LEDGER_LIMIT = 2000;

    @Transactional(readOnly = true)
    public LedgerDto ledger(Long accountId, LocalDate from, LocalDate to, Long branchId) {
        Account a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account"));
        requireRange(from, to);
        boolean debitNormal = a.getType().section().debitNormal();
        BigDecimal[] before = sums(null, from.minusDays(1), branchId).get(a.getId());
        BigDecimal opening = a.getType().section().profitAndLoss() ? BigDecimal.ZERO : balance(a, before);
        Map<String, Object> p = new HashMap<>();
        p.put("account", accountId);
        p.put("from", from);
        p.put("to", to);
        p.put("branch", branchId);
        List<LedgerLine> lines = new ArrayList<>();
        BigDecimal[] running = {opening};
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<Object[]> rows = jdbc.sql("""
                        SELECT e.id, e.entry_number, e.entry_date, e.source, e.source_id, e.reference, e.memo, l.memo AS line_memo,
                               COALESCE(v.name, c.name) AS party, l.debit, l.credit
                        FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                        LEFT JOIN vendors v ON v.id = l.vendor_id LEFT JOIN customers c ON c.id = l.customer_id
                        WHERE l.account_id = :account AND e.entry_date BETWEEN :from AND :to
                          AND (:branch IS NULL OR e.branch_id = :branch)
                        ORDER BY e.entry_date, e.id, l.line_no
                        """).params(p)
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4),
                        rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10), rs.getBigDecimal(11)})
                .list();
        for (Object[] r : rows) {
            BigDecimal dr = (BigDecimal) r[9];
            BigDecimal cr = (BigDecimal) r[10];
            debits = debits.add(dr);
            credits = credits.add(cr);
            running[0] = running[0].add(debitNormal ? dr.subtract(cr) : cr.subtract(dr));
            if (lines.size() < LEDGER_LIMIT) {
                String memo = r[7] == null ? (String) r[6] : r[6] + " - " + r[7];
                lines.add(new LedgerLine((Long) r[0], (String) r[1], (LocalDate) r[2], JournalSource.valueOf((String) r[3]), (Long) r[4],
                        (String) r[5], memo, (String) r[8], dr, cr, running[0]));
            }
        }
        return new LedgerDto(AccountDto.from(a, running[0]), from, to, opening, debits, credits, running[0], lines,
                rows.size() > LEDGER_LIMIT);
    }

    // ------------------------------------------------------------------------------------- journal

    @Transactional(readOnly = true)
    public Page<EntryDto> journal(LocalDate from, LocalDate to, Long branchId, JournalSource source, Long accountId, String q,
                                  Pageable pageable) {
        requireRange(from, to);
        return entries.search(from, to, branchId, source, accountId, blank(q), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public EntryDto entry(Long id) {
        return toDto(entries.findById(id).orElseThrow(() -> ApiException.notFound("Journal entry")));
    }

    /** A journal entry the accountant writes: balanced, on an open date, active accounts. */
    @Transactional
    public EntryDto postManual(JournalRequest req, AuthUser user) {
        AccountingSettings s = ledger.settings();
        requireFull(s);
        ledger.requireOpen(s, req.date());
        if (req.branchId() != null) {
            branchContext.requireBranch(user, req.branchId());
        }
        Ledger.Draft d = new Ledger.Draft(JournalSource.MANUAL, req.date(), req.memo().trim())
                .branch(req.branchId()).source(null, null, blank(req.reference()));
        for (JournalLineRequest l : req.lines()) {
            boolean hasDebit = l.debit() != null && l.debit().signum() > 0;
            boolean hasCredit = l.credit() != null && l.credit().signum() > 0;
            if (hasDebit == hasCredit) {
                throw ApiException.badRequest("Each line needs a debit or a credit, not both");
            }
            Account a = ledger.pick(l.accountId(), t -> true, "usable");
            d.party(vendorId(l.vendorId()), customerId(l.customerId()));
            d.add(a, hasDebit ? l.debit() : l.credit(), hasDebit, blank(l.memo()));
        }
        JournalEntry e = ledger.post(d, user.id()).orElseThrow(() -> ApiException.badRequest("The entry has no amounts"));
        audit.log(AuditAction.JOURNAL_POSTED, "JournalEntry", e.getId(), "Posted " + e.getEntryNumber() + " on " + e.getEntryDate()
                + ": " + e.getMemo() + " (" + e.getTotal().toPlainString() + ")");
        return toDto(e);
    }

    /** Cancels a manual entry (or the opening balances) with a reversal; the original stays. */
    @Transactional
    public EntryDto reverse(Long id, ReverseRequest req, AuthUser user) {
        requireFull(ledger.settings());
        JournalEntry e = entries.findById(id).orElseThrow(() -> ApiException.notFound("Journal entry"));
        if (!e.getSource().reversible()) {
            throw ApiException.badRequest(e.getSource().label() + " entries are corrected through their document "
                    + "(a return, a voided invoice, a stock adjustment...), not reversed here");
        }
        if (entries.findByReversalOfId(e.getId()).isPresent()) {
            throw ApiException.conflict(e.getEntryNumber() + " was already reversed");
        }
        AccountingSettings s = ledger.settings();
        LocalDate date = req.date() != null ? req.date() : today();
        ledger.requireOpen(s, date);
        JournalEntry r = ledger.post(ledger.reversal(e, date, "Reversal of " + e.getEntryNumber() + ": " + req.reason().trim()), user.id())
                .orElseThrow();
        audit.log(AuditAction.JOURNAL_REVERSED, "JournalEntry", e.getId(), "Reversed " + e.getEntryNumber() + " with "
                + r.getEntryNumber() + ": " + req.reason().trim());
        return toDto(r);
    }

    private EntryDto toDto(JournalEntry e) {
        JournalEntry reversedBy = entries.findByReversalOfId(e.getId()).orElse(null);
        return new EntryDto(e.getId(), e.getEntryNumber(), e.getEntryDate(), e.getBranch() == null ? null : e.getBranch().getId(),
                e.getBranch() == null ? null : e.getBranch().getName(), e.getSource(), e.getSourceId(), e.getReference(), e.getMemo(),
                e.getTotal(), e.getReversalOf() == null ? null : e.getReversalOf().getId(),
                e.getReversalOf() == null ? null : e.getReversalOf().getEntryNumber(),
                reversedBy == null ? null : reversedBy.getId(), reversedBy == null ? null : reversedBy.getEntryNumber(),
                e.getSource().reversible() && reversedBy == null, e.getCreatedBy() == null ? null : e.getCreatedBy().getFullName(),
                e.getCreatedAt(),
                e.getLines().stream().map(l -> new LineDto(l.getAccount().getId(), l.getAccount().getCode(), l.getAccount().getName(),
                        l.getAccount().getType(), l.getDebit(), l.getCredit(), l.getMemo(),
                        l.getVendor() == null ? null : l.getVendor().getId(), l.getVendor() == null ? null : l.getVendor().getName(),
                        l.getCustomer() == null ? null : l.getCustomer().getId(),
                        l.getCustomer() == null ? null : l.getCustomer().getName())).toList());
    }

    // ------------------------------------------------------------------- start and close the books

    @Transactional(readOnly = true)
    public SettingsDto settings() {
        return SettingsDto.from(ledger.settings());
    }

    /**
     * Opening balances from what Munita already knows: stock at cost, what is owed to each vendor,
     * and deliveries not yet invoiced. Cash and bank are left for the accountant to count.
     */
    @Transactional(readOnly = true)
    public List<SuggestedLine> suggestedOpening() {
        List<SuggestedLine> out = new ArrayList<>();
        Account inventory = ledger.account(SystemAccount.INVENTORY);
        BigDecimal stock = ledger.stockValueNow();
        if (stock.signum() != 0) {
            out.add(line(inventory, stock, true, null, "Stock on hand at catalog cost"));
        }
        Account payables = ledger.account(SystemAccount.PAYABLES);
        for (OpenPurchases.VendorOwed v : purchases.owedByVendor()) {
            out.add(line(payables, v.owed(), false, v.vendorId(), "Owed to " + v.vendorName()));
        }
        BigDecimal notInvoiced = purchases.receivedNotInvoiced();
        if (notInvoiced.signum() > 0) {
            out.add(line(ledger.account(SystemAccount.GOODS_NOT_INVOICED), notInvoiced, false, null,
                    "Deliveries received, vendor invoice not recorded yet"));
        }
        return out;
    }

    private static SuggestedLine line(Account a, BigDecimal amount, boolean debit, Long vendorId, String memo) {
        return new SuggestedLine(a.getId(), a.getCode(), a.getName(), debit ? amount : BigDecimal.ZERO, debit ? BigDecimal.ZERO : amount,
                vendorId, memo);
    }

    /**
     * Starts the books: from {@code startDate} on, everything posts. The opening balances are one
     * entry on that date; whatever doesn't balance goes to the Opening balances equity account.
     */
    @Transactional
    public SettingsDto setup(SetupRequest req, AuthUser user) {
        AccountingSettings s = settingsRepo.findById(1L).orElseThrow();
        if (s.started()) {
            throw ApiException.conflict("The books were already started on " + s.getStartDate());
        }
        if (req.startDate().isAfter(today().plusDays(31))) {
            throw ApiException.badRequest("Pick a start date within the next month");
        }
        Account opening = ledger.account(SystemAccount.OPENING);
        Ledger.Draft d = new Ledger.Draft(JournalSource.OPENING, req.startDate(), "Opening balances")
                .source(null, "OPENING", null);
        BigDecimal net = BigDecimal.ZERO;
        for (OpeningLine l : req.lines() == null ? List.<OpeningLine>of() : req.lines()) {
            boolean hasDebit = l.debit() != null && l.debit().signum() > 0;
            boolean hasCredit = l.credit() != null && l.credit().signum() > 0;
            if (!hasDebit && !hasCredit) {
                continue;
            }
            if (hasDebit && hasCredit) {
                throw ApiException.badRequest("Each opening balance is a debit or a credit, not both");
            }
            Account a = ledger.pick(l.accountId(), t -> !t.section().profitAndLoss(), "a balance sheet account");
            d.party(vendorId(l.vendorId()), null);
            d.add(a, hasDebit ? l.debit() : l.credit(), hasDebit, blank(l.memo()));
            net = net.add(hasDebit ? l.debit() : l.credit().negate());
        }
        d.party(null, null).add(opening, net, false, "Balancing amount");
        s.setStartDate(req.startDate());
        s.setMode(req.mode() == null ? AccountingMode.FULL : req.mode());
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
        settingsRepo.flush();
        JournalEntry e = ledger.post(d, user.id()).orElse(null);
        audit.log(AuditAction.BOOKS_STARTED, "AccountingSettings", 1L, "Started the books on " + req.startDate()
                + (s.full() ? " (full accounting)" : " (normal accounting)")
                + (e == null ? " with no opening balances" : " with opening balances " + e.getEntryNumber() + " (" + e.getTotal().toPlainString() + ")"));
        return SettingsDto.from(s);
    }

    /** Switches between normal and full accounting. Nothing posted changes; only what can be done. */
    @Transactional
    public SettingsDto mode(AccountingDtos.ModeRequest req, AuthUser user) {
        AccountingSettings s = settingsRepo.findById(1L).orElseThrow();
        if (!s.started()) {
            throw ApiException.conflict("Start the books first (Accounting > Setup)");
        }
        if (s.getMode() != req.mode()) {
            Changes changes = new Changes().add("Mode", label(s.getMode()), label(req.mode()));
            s.setMode(req.mode());
            s.setUpdatedBy(users.getReferenceById(user.id()));
            s.setUpdatedAt(Instant.now());
            audit.log(AuditAction.ACCOUNTING_MODE_CHANGED, "AccountingSettings", 1L, "Switched to " + label(req.mode()), changes);
        }
        return SettingsDto.from(s);
    }

    private static String label(AccountingMode m) {
        return m == AccountingMode.FULL ? "full accounting" : "normal accounting";
    }

    /** Manual journal entries and reversals need accounting knowledge: full accounting only. */
    private static void requireFull(AccountingSettings s) {
        if (s.started() && !s.full()) {
            throw ApiException.conflict("Journal entries are part of full accounting: switch to it in Accounting > Setup");
        }
    }

    /** Closes the books up to {@code lockDate} (nothing can be posted on or before it); empty reopens them. */
    @Transactional
    public SettingsDto lock(LockRequest req, AuthUser user) {
        AccountingSettings s = settingsRepo.findById(1L).orElseThrow();
        if (!s.started()) {
            throw ApiException.conflict("Start the books first (Accounting > Setup)");
        }
        LocalDate lock = req.lockDate();
        if (lock != null && lock.isAfter(today())) {
            throw ApiException.badRequest("The books can only be closed up to today");
        }
        if (lock != null && lock.isBefore(s.getStartDate())) {
            throw ApiException.badRequest("The books start on " + s.getStartDate());
        }
        Changes changes = new Changes().add("Closed up to", str(s.getLockDate()), str(lock));
        s.setLockDate(lock);
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
        if (!changes.isEmpty()) {
            audit.log(AuditAction.BOOKS_LOCKED, "AccountingSettings", 1L, lock == null ? "Reopened the books"
                    : "Closed the books up to " + lock, changes);
        }
        return SettingsDto.from(s);
    }

    // ------------------------------------------------------------------------------------ overview

    @Transactional(readOnly = true)
    public OverviewDto overview(Long branchId) {
        AccountingSettings s = ledger.settings();
        LocalDate today = today();
        LocalDate monthStart = today.withDayOfMonth(1);
        Map<Long, BigDecimal[]> all = sums(null, today, branchId);
        Map<Long, BigDecimal[]> month = sums(monthStart, today, branchId);
        List<Account> list = accounts.findAllByOrderByCodeAsc();
        List<AccountDto> money = list.stream().filter(a -> a.getType().money() && (a.isActive() || nonZero(all.get(a.getId()))))
                .map(a -> AccountDto.from(a, balance(a, all.get(a.getId())))).toList();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal cos = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        for (Account a : list) {
            BigDecimal b = balance(a, month.get(a.getId()));
            switch (a.getType()) {
                case REVENUE, OTHER_INCOME -> income = income.add(b);
                case COST_OF_SALES -> cos = cos.add(b);
                case EXPENSE, OTHER_EXPENSE -> expenses = expenses.add(b);
                default -> {
                }
            }
        }
        BigDecimal payables = balance(ledger.account(SystemAccount.PAYABLES), all.get(ledger.account(SystemAccount.PAYABLES).getId()));
        Account out = ledger.account(SystemAccount.VAT_OUTPUT);
        Account in = ledger.account(SystemAccount.VAT_INPUT);
        BigDecimal vat = balance(out, all.get(out.getId())).subtract(balance(in, all.get(in.getId())));
        Account inv = ledger.account(SystemAccount.INVENTORY);
        // Inventory isn't per branch in the books (transfers don't post), so compare company-wide, every
        // entry (one posted on the next open day after a closed period included).
        BigDecimal invBooks = balance(inv, sums(null, null, null).get(inv.getId()));
        return new OverviewDto(SettingsDto.from(s), money, income, cos, expenses, income.subtract(cos).subtract(expenses), payables, vat,
                invBooks, ledger.stockValueNow(), monthStart);
    }

    // ---------------------------------------------------------------- other income and expenses

    @Transactional(readOnly = true)
    public Page<OtherDto> others(LocalDate from, LocalDate to, OtherTransaction.Kind kind, Long branchId, Long accountId, String q,
                                 Pageable pageable) {
        requireRange(from, to);
        return others.search(from, to, kind, branchId, accountId, blank(q), pageable).map(OtherDto::from);
    }

    /**
     * Records other income (money in: the income account and any VAT charged) or an other expense
     * (money out: the expense account and any VAT paid), and posts it.
     */
    @Transactional
    public OtherDto record(OtherRequest req, AuthUser user) {
        AccountingSettings s = ledger.settings();
        ledger.requireOpen(s, req.date());
        if (req.date().isAfter(today())) {
            throw ApiException.badRequest("The date is in the future");
        }
        boolean income = req.kind() == OtherTransaction.Kind.INCOME;
        Long branchId = req.branchId() != null ? req.branchId() : branchContext.currentBranchId(user);
        branchContext.requireBranch(user, branchId);
        Account account = income ? ledger.pick(req.accountId(), AccountType::income, "an income account") : ledger.expenseAccount(req.accountId());
        Account money = ledger.moneyAccount(req.moneyAccountId());
        BigDecimal tax = req.tax() == null ? BigDecimal.ZERO : req.tax();
        if (tax.compareTo(req.amount()) >= 0) {
            throw ApiException.badRequest("The tax must be less than the amount");
        }
        OtherTransaction t = new OtherTransaction();
        t.setKind(req.kind());
        t.setDate(req.date());
        t.setBranch(branches.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch")));
        t.setAccount(account);
        t.setMoneyAccount(money);
        t.setAmount(req.amount());
        t.setTax(tax);
        t.setParty(blank(req.party()));
        t.setVendor(req.vendorId() == null ? null : vendors.findById(req.vendorId()).orElseThrow(() -> ApiException.notFound("Vendor")));
        t.setCustomer(req.customerId() == null ? null
                : customers.findById(req.customerId()).orElseThrow(() -> ApiException.notFound("Customer")));
        t.setReference(blank(req.reference()));
        t.setNote(blank(req.note()));
        t.setCreatedBy(users.getReferenceById(user.id()));
        others.saveAndFlush(t);
        t.setNumber(String.format(income ? "INC-%05d" : "EXP-%05d", t.getId()));

        String who = t.getParty() != null ? t.getParty() : t.getVendor() != null ? t.getVendor().getName()
                : t.getCustomer() != null ? t.getCustomer().getName() : null;
        String memo = account.getName() + (who == null ? "" : " - " + who) + (t.getNote() == null ? "" : ": " + t.getNote());
        Ledger.Draft d = new Ledger.Draft(income ? JournalSource.OTHER_INCOME : JournalSource.OTHER_EXPENSE, t.getDate(), memo)
                .branch(branchId)
                .source(t.getId(), "OTHER:" + t.getId(), t.getNumber())
                .party(t.getVendor() == null ? null : t.getVendor().getId(), t.getCustomer() == null ? null : t.getCustomer().getId())
                .add(money, t.getAmount(), income, t.getReference())
                .add(account, t.getAmount().subtract(tax), !income, null)
                .add(ledger.account(income ? SystemAccount.VAT_OUTPUT : SystemAccount.VAT_INPUT), tax, !income, null);
        t.setJournalEntry(ledger.post(d, user.id()).orElseThrow());
        audit.log(income ? AuditAction.OTHER_INCOME_RECORDED : AuditAction.OTHER_EXPENSE_RECORDED, "OtherTransaction", t.getId(),
                (income ? "Recorded income " : "Recorded expense ") + t.getNumber() + ": " + memo + ", " + t.getAmount().toPlainString()
                        + (tax.signum() > 0 ? " (tax " + tax.toPlainString() + ")" : "") + (income ? " into " : " from ") + money.label());
        return OtherDto.from(t);
    }

    /** Voids a recorded income or expense: it stays listed, and a reversal cancels its entry. */
    @Transactional
    public OtherDto voidOther(Long id, String reason, AuthUser user) {
        OtherTransaction t = others.lockById(id).orElseThrow(() -> ApiException.notFound("Transaction"));
        branchContext.requireBranch(user, t.getBranch().getId());
        if (t.getStatus() == OtherTransaction.Status.VOID) {
            return OtherDto.from(t);
        }
        AccountingSettings s = ledger.settings();
        LocalDate date = ledger.openDate(s, today());
        ledger.post(ledger.reversal(t.getJournalEntry(), date, "Voided " + t.getNumber() + ": " + reason.trim()), user.id());
        t.setStatus(OtherTransaction.Status.VOID);
        t.setVoidedBy(users.getReferenceById(user.id()));
        t.setVoidedAt(Instant.now());
        t.setVoidReason(reason.trim());
        audit.log(AuditAction.OTHER_TRANSACTION_VOIDED, "OtherTransaction", t.getId(), "Voided " + t.getNumber() + " ("
                + t.getAmount().toPlainString() + "): " + reason.trim());
        return OtherDto.from(t);
    }

    // ------------------------------------------------------------------------------------- helpers

    /** Per account: [debits, credits] for entries dated from..to (from null: since the start). */
    Map<Long, BigDecimal[]> sums(LocalDate from, LocalDate to, Long branchId) {
        Map<String, Object> p = new HashMap<>();
        p.put("from", from);
        p.put("to", to);
        p.put("branch", branchId);
        Map<Long, BigDecimal[]> out = new HashMap<>();
        jdbc.sql("""
                        SELECT l.account_id, SUM(l.debit) AS dr, SUM(l.credit) AS cr
                        FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                        WHERE (:from IS NULL OR e.entry_date >= :from) AND (:to IS NULL OR e.entry_date <= :to)
                          AND (:branch IS NULL OR e.branch_id = :branch)
                        GROUP BY l.account_id
                        """).params(p)
                .query((rs, i) -> out.put(rs.getLong(1), new BigDecimal[]{rs.getBigDecimal(2), rs.getBigDecimal(3)})).list();
        return out;
    }

    /** The balance on the account's normal side. */
    static BigDecimal balance(Account a, BigDecimal[] sums) {
        if (sums == null) {
            return BigDecimal.ZERO;
        }
        return a.getType().section().debitNormal() ? sums[0].subtract(sums[1]) : sums[1].subtract(sums[0]);
    }

    private static boolean nonZero(BigDecimal[] sums) {
        return sums != null && sums[0].compareTo(sums[1]) != 0;
    }

    private Long vendorId(Long id) {
        if (id != null && !vendors.existsById(id)) {
            throw ApiException.notFound("Vendor");
        }
        return id;
    }

    private Long customerId(Long id) {
        if (id != null && !customers.existsById(id)) {
            throw ApiException.notFound("Customer");
        }
        return id;
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("The start date is after the end date");
        }
        if (from.plusYears(5).isBefore(to)) {
            throw ApiException.badRequest("Pick a range of at most five years");
        }
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
