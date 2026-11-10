package com.pos.accounting;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.accounting.AccountingDtos.AccountDto;
import com.pos.accounting.AccountingDtos.AccountOption;
import com.pos.accounting.AccountingDtos.AccountRequest;
import com.pos.accounting.AccountingDtos.EntryDto;
import com.pos.accounting.AccountingDtos.JournalRequest;
import com.pos.accounting.AccountingDtos.LedgerDto;
import com.pos.accounting.AccountingDtos.LockRequest;
import com.pos.accounting.AccountingDtos.OtherDto;
import com.pos.accounting.AccountingDtos.OtherRequest;
import com.pos.accounting.AccountingDtos.OverviewDto;
import com.pos.accounting.AccountingDtos.ReverseRequest;
import com.pos.accounting.AccountingDtos.SettingsDto;
import com.pos.accounting.AccountingDtos.SetupRequest;
import com.pos.accounting.AccountingDtos.SuggestedLine;
import com.pos.accounting.AccountingDtos.VoidRequest;
import com.pos.auth.AuthUser;
import com.pos.common.PageResponse;
import com.pos.inventory.BranchContext;
import com.pos.settings.SettingsService;

import jakarta.validation.Valid;

/**
 * The Accounting module. Seeing it needs "View accounting"; recording income and expenses,
 * posting journal entries and managing the chart and the books each need their own permission.
 * Lists follow the branch rules: without "All branches", only the person's own branch.
 */
@RestController
@RequestMapping("/accounting")
public class AccountingController {

    private final AccountingService service;
    private final BranchContext branches;
    private final SettingsService settings;

    public AccountingController(AccountingService service, BranchContext branches, SettingsService settings) {
        this.service = service;
        this.branches = branches;
        this.settings = settings;
    }

    private LocalDate today() {
        return LocalDate.now(settings.current().timezone());
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public OverviewDto overview(@RequestParam(required = false) Long branchId, @AuthenticationPrincipal AuthUser user) {
        return service.overview(branches.viewScope(user, branchId));
    }

    // --- accounts

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public List<AccountDto> accounts(@RequestParam(required = false) LocalDate asOf, @RequestParam(required = false) Long branchId,
                                     @AuthenticationPrincipal AuthUser user) {
        return service.accounts(asOf != null ? asOf : today(), branches.viewScope(user, branchId));
    }

    /** Pickers: money, expense, income or all. Purchasing uses them for invoices and payments. */
    @GetMapping("/accounts/options")
    @PreAuthorize("hasAnyAuthority('ACCOUNTING_VIEW','PURCHASE_INVOICE','PURCHASE_PAY')")
    public List<AccountOption> options(@RequestParam(required = false) String purpose) {
        return service.options(purpose);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    public AccountDto createAccount(@Valid @RequestBody AccountRequest req) {
        return service.createAccount(req);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    public AccountDto updateAccount(@PathVariable Long id, @Valid @RequestBody AccountRequest req) {
        return service.updateAccount(id, req);
    }

    @GetMapping("/accounts/{id}/ledger")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public LedgerDto ledger(@PathVariable Long id, @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long branchId,
                            @AuthenticationPrincipal AuthUser user) {
        LocalDate end = to != null ? to : today();
        return service.ledger(id, from != null ? from : end.withDayOfMonth(1), end, branches.viewScope(user, branchId));
    }

    // --- journal

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public PageResponse<EntryDto> journal(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                          @RequestParam(required = false) Long branchId, @RequestParam(required = false) JournalSource source,
                                          @RequestParam(required = false) Long accountId, @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                                          @AuthenticationPrincipal AuthUser user) {
        LocalDate end = to != null ? to : today();
        return PageResponse.of(service.journal(from != null ? from : end.withDayOfMonth(1), end, branches.viewScope(user, branchId),
                source, accountId, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))), e -> e);
    }

    @GetMapping("/journal/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public EntryDto entry(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        EntryDto e = service.entry(id);
        // Without "All branches", only their own branch's entries.
        if (!user.allBranches() && (e.branchId() == null || !e.branchId().equals(user.branchId()))) {
            throw com.pos.common.ApiException.notFound("Journal entry");
        }
        return e;
    }

    @PostMapping("/journal")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_JOURNAL')")
    public EntryDto post(@Valid @RequestBody JournalRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.postManual(req, user);
    }

    @PostMapping("/journal/{id}/reverse")
    @PreAuthorize("hasAuthority('ACCOUNTING_JOURNAL')")
    public EntryDto reverse(@PathVariable Long id, @Valid @RequestBody ReverseRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.reverse(id, req, user);
    }

    // --- the books

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public SettingsDto settings() {
        return service.settings();
    }

    @GetMapping("/setup/suggested")
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    public List<SuggestedLine> suggested() {
        return service.suggestedOpening();
    }

    @PostMapping("/setup")
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    public SettingsDto setup(@Valid @RequestBody SetupRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.setup(req, user);
    }

    @PutMapping("/mode")
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    public SettingsDto mode(@Valid @RequestBody AccountingDtos.ModeRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.mode(req, user);
    }

    @PutMapping("/lock")
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    public SettingsDto lock(@RequestBody LockRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.lock(req, user);
    }

    // --- other income and expenses

    @GetMapping("/other")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    public PageResponse<OtherDto> others(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                         @RequestParam(required = false) OtherTransaction.Kind kind,
                                         @RequestParam(required = false) Long branchId, @RequestParam(required = false) Long accountId,
                                         @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal AuthUser user) {
        LocalDate end = to != null ? to : today();
        return PageResponse.of(service.others(from != null ? from : end.withDayOfMonth(1), end, kind, branches.viewScope(user, branchId),
                accountId, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))), t -> t);
    }

    @PostMapping("/other")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_RECORD')")
    public OtherDto record(@Valid @RequestBody OtherRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.record(req, user);
    }

    @PostMapping("/other/{id}/void")
    @PreAuthorize("hasAuthority('ACCOUNTING_RECORD')")
    public OtherDto voidOther(@PathVariable Long id, @Valid @RequestBody VoidRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.voidOther(id, req.reason(), user);
    }
}
