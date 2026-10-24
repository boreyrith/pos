package com.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pos.auth.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An applied stock adjustment or stock count in one storage. Never edited afterwards. */
@Entity
@Table(name = "stock_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adjustment_number", unique = true, length = 30)
    private String adjustmentNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_id")
    private Storage storage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AdjustmentKind kind;

    @Column(length = 255)
    private String note;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "value_change", nullable = false, precision = 14, scale = 2)
    private BigDecimal valueChange = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<StockAdjustmentLine> lines = new ArrayList<>();
}
