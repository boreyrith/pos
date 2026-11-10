package com.pos.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Requests and responses of the accounting API. Amounts are in the base currency. */
public final class AccountingDtos {

    private AccountingDtos() {
    }

    // --- accounts

    /** {@code balance}: on its normal side (a positive cash balance is money held; a positive payable is owed). */
    public record AccountDto(Long id, String code, String name, AccountType type, AccountType.Section section, boolean system,
                             String systemKey, Long branchId, String branchName, String description, boolean active,
                             BigDecimal balance) {

        static AccountDto from(Account a, BigDecimal balance) {
            return new AccountDto(a.getId(), a.getCode(), a.getName(), a.getType(), a.getType().section(), a.isSystem(),
                    a.getSystemKey(), a.getBranch() == null ? null : a.getBranch().getId(),
                    a.getBranch() == null ? null : a.getBranch().getName(), a.getDescription(), a.isActive(), balance);
        }
    }

    public record AccountRequest(@NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9.-]+") String code,
                                 @NotBlank @Size(max = 100) String name, @NotNull AccountType type,
                                 @Size(max = 255) String description, Boolean active) {
    }

    /** For pickers: active accounts of a kind. */
    public record AccountOption(Long id, String code, String name, AccountType type, Long branchId) {

        static AccountOption from(Account a) {
            return new AccountOption(a.getId(), a.getCode(), a.getName(), a.getType(), a.getBranch() == null ? null : a.getBranch().getId());
        }
    }

    // --- journal

    public record LineDto(Long accountId, String accountCode, String accountName, AccountType accountType, BigDecimal debit,
                          BigDecimal credit, String memo, Long vendorId, String vendorName, Long customerId, String customerName) {
    }

    /**
     * {@code sourceId}: the document (order, purchase order, invoice, adjustment, catalog entry, other
     * income or expense) it came from, for a link.
     */
    public record EntryDto(Long id, String entryNumber, LocalDate date, Long branchId, String branchName, JournalSource source,
                           Long sourceId, String reference, String memo, BigDecimal total, Long reversalOfId,
                           String reversalOfNumber, Long reversedById, String reversedByNumber, boolean reversible,
                           String createdBy, Instant createdAt, List<LineDto> lines) {
    }

    public record JournalLineRequest(@NotNull Long accountId,
                                     @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal debit,
                                     @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal credit,
                                     @Size(max = 255) String memo, Long vendorId, Long customerId) {
    }

    public record JournalRequest(@NotNull LocalDate date, @NotBlank @Size(max = 255) String memo, @Size(max = 60) String reference,
                                 Long branchId, @NotEmpty @Size(min = 2, max = 100) List<@Valid JournalLineRequest> lines) {
    }

    /** {@code date}: when the reversal posts (today if empty). */
    public record ReverseRequest(LocalDate date, @NotBlank @Size(max = 200) String reason) {
    }

    // --- ledger

    public record LedgerLine(Long entryId, String entryNumber, LocalDate date, JournalSource source, Long sourceId, String reference,
                             String memo, String party, BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    /** One account's movements in a period, with the balance before, after each line and at the end. */
    public record LedgerDto(AccountDto account, LocalDate from, LocalDate to, BigDecimal opening, BigDecimal debits,
                            BigDecimal credits, BigDecimal closing, List<LedgerLine> lines, boolean truncated) {
    }

    // --- the books' dates and opening balances

    public record SettingsDto(boolean started, AccountingMode mode, LocalDate startDate, LocalDate lockDate, LocalDate firstOpenDate) {

        static SettingsDto from(AccountingSettings s) {
            return new SettingsDto(s.started(), s.getMode(), s.getStartDate(), s.getLockDate(), s.started() ? s.firstOpenDate() : null);
        }
    }

    /** An opening balance: one side per line. {@code vendorId} on payables says whose. */
    public record OpeningLine(@NotNull Long accountId, @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal debit,
                              @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal credit, Long vendorId,
                              @Size(max = 255) String memo) {
    }

    /**
     * Starts the books on {@code startDate} in {@code mode} (FULL if empty), with the opening
     * balances; any difference between them goes to Opening balances. In NORMAL mode the screen
     * sends what Munita knows (stock, payables) plus the money counted in each drawer and bank.
     */
    public record SetupRequest(@NotNull LocalDate startDate, AccountingMode mode, @Size(max = 300) List<@Valid OpeningLine> lines) {
    }

    /** Switches mode; the books and everything posted stay as they are. */
    public record ModeRequest(@NotNull AccountingMode mode) {
    }

    /** Opening balances worked out from what Munita already knows, for the accountant to check. */
    public record SuggestedLine(Long accountId, String accountCode, String accountName, BigDecimal debit, BigDecimal credit,
                                Long vendorId, String memo) {
    }

    public record LockRequest(LocalDate lockDate) {
    }

    // --- overview

    /**
     * The books at a glance. Profit figures are this month so far; {@code inventoryBooks} is the
     * Inventory account and {@code inventoryStock} the stock valuation, which should agree.
     */
    public record OverviewDto(SettingsDto settings, List<AccountDto> money, BigDecimal monthIncome, BigDecimal monthCostOfSales,
                              BigDecimal monthExpenses, BigDecimal monthProfit, BigDecimal payables, BigDecimal vatOwed,
                              BigDecimal inventoryBooks, BigDecimal inventoryStock, LocalDate monthStart) {
    }

    // --- other income and expenses

    /** {@code amount}: paid, tax included; {@code tax}: the VAT in it. Branch: the person's own if empty. */
    public record OtherRequest(@NotNull OtherTransaction.Kind kind, @NotNull LocalDate date, Long branchId, @NotNull Long accountId,
                               @NotNull Long moneyAccountId,
                               @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                               @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal tax,
                               @Size(max = 150) String party, Long vendorId, Long customerId, @Size(max = 60) String reference,
                               @Size(max = 255) String note) {
    }

    public record OtherDto(Long id, String number, OtherTransaction.Kind kind, LocalDate date, Long branchId, String branchName,
                           Long accountId, String account, Long moneyAccountId, String moneyAccount, BigDecimal amount, BigDecimal tax,
                           String party, Long vendorId, String vendorName, Long customerId, String customerName, String reference,
                           String note, OtherTransaction.Status status, Long entryId, String entryNumber, String createdBy,
                           Instant createdAt, String voidedBy, Instant voidedAt, String voidReason) {

        static OtherDto from(OtherTransaction t) {
            return new OtherDto(t.getId(), t.getNumber(), t.getKind(), t.getDate(), t.getBranch().getId(), t.getBranch().getName(),
                    t.getAccount().getId(), t.getAccount().label(), t.getMoneyAccount().getId(), t.getMoneyAccount().label(),
                    t.getAmount(), t.getTax(), t.getParty(), t.getVendor() == null ? null : t.getVendor().getId(),
                    t.getVendor() == null ? null : t.getVendor().getName(), t.getCustomer() == null ? null : t.getCustomer().getId(),
                    t.getCustomer() == null ? null : t.getCustomer().getName(), t.getReference(), t.getNote(), t.getStatus(),
                    t.getJournalEntry() == null ? null : t.getJournalEntry().getId(),
                    t.getJournalEntry() == null ? null : t.getJournalEntry().getEntryNumber(), t.getCreatedBy().getFullName(),
                    t.getCreatedAt(), t.getVoidedBy() == null ? null : t.getVoidedBy().getFullName(), t.getVoidedAt(), t.getVoidReason());
        }
    }

    public record VoidRequest(@NotBlank @Size(max = 200) String reason) {
    }
}
