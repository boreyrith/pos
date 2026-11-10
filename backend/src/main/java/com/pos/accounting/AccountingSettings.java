package com.pos.accounting;

import java.time.Instant;
import java.time.LocalDate;

import com.pos.auth.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The books' dates: one row (id 1). See V31__accounting.sql. */
@Entity
@Table(name = "accounting_settings")
@Getter
@Setter
@NoArgsConstructor
public class AccountingSettings {

    @Id
    private Long id;

    /** Nothing posts before it; null until the books are started. */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** Closed up to and including this date. */
    @Column(name = "lock_date")
    private LocalDate lockDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountingMode mode = AccountingMode.NORMAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean full() {
        return mode == AccountingMode.FULL;
    }

    public boolean started() {
        return startDate != null;
    }

    /** The first date entries can be posted on. */
    public LocalDate firstOpenDate() {
        LocalDate open = lockDate == null ? startDate : lockDate.plusDays(1);
        return startDate == null || open.isAfter(startDate) ? open : startDate;
    }
}
