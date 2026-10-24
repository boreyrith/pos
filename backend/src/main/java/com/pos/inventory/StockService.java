package com.pos.inventory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.ApiException;

/**
 * The only place stock quantities change. Every change is a single SQL statement on the
 * (storage, entry) row, so concurrent sales and stock edits never overwrite each other, and every
 * change writes a history line with the new balance. Must run inside the caller's transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StockService {

    /** Who and what a change belongs to, for the history. All fields are optional. */
    public record Ref(Long userId, Long orderId, Long transferId, String note, Long adjustmentId,
                      AdjustmentReason reason, Long receiptId) {

        public Ref(Long userId, Long orderId, Long transferId, String note, Long adjustmentId, AdjustmentReason reason) {
            this(userId, orderId, transferId, note, adjustmentId, reason, null);
        }

        public Ref(Long userId, Long orderId, Long transferId, String note) {
            this(userId, orderId, transferId, note, null, null, null);
        }

        /** A delivery from a vendor. */
        public static Ref receipt(Long userId, Long receiptId, String note) {
            return new Ref(userId, null, null, note, null, null, receiptId);
        }

        public static Ref user(Long userId) {
            return new Ref(userId, null, null, null);
        }
    }

    public record LevelRow(Long branchId, String branchName, Long storageId, String storageName, boolean selling,
                           boolean storageActive, int quantity) {
    }

    private final JdbcClient jdbc;

    public StockService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds stock and returns the new balance. */
    public int add(Long storageId, Long offeringId, int quantity, MovementType type, Ref ref) {
        requirePositive(quantity);
        jdbc.sql("""
                        INSERT INTO stock_levels (storage_id, offering_id, quantity) VALUES (:s, :o, :q) AS new
                        ON DUPLICATE KEY UPDATE quantity = stock_levels.quantity + new.quantity
                        """)
                .param("s", storageId).param("o", offeringId).param("q", quantity).update();
        int balance = quantity(storageId, offeringId);
        record(storageId, offeringId, type, quantity, balance, ref);
        return balance;
    }

    /** Takes stock and returns the new balance; fails without changing anything if there isn't enough. */
    public int take(Long storageId, Long offeringId, int quantity, MovementType type, Ref ref) {
        requirePositive(quantity);
        int updated = jdbc.sql("""
                        UPDATE stock_levels SET quantity = quantity - :q
                        WHERE storage_id = :s AND offering_id = :o AND quantity >= :q
                        """)
                .param("s", storageId).param("o", offeringId).param("q", quantity).update();
        if (updated == 0) {
            int available = quantity(storageId, offeringId);
            throw ApiException.conflict("Not enough stock of " + offeringName(offeringId) + " in "
                    + storageName(storageId) + " (" + available + " left, " + quantity + " needed)");
        }
        int balance = quantity(storageId, offeringId);
        record(storageId, offeringId, type, -quantity, balance, ref);
        return balance;
    }

    /** Sets stock to a counted amount and records the difference as a COUNT movement. */
    public int set(Long storageId, Long offeringId, int counted, Ref ref) {
        setAndReturnChange(storageId, offeringId, counted, ref, MovementType.COUNT);
        return counted;
    }

    /**
     * Sets stock to {@code target} and returns the change (target - previous). The movement is
     * recorded with {@code type}, or ADJUSTMENT_IN/OUT by sign when type is null; nothing is
     * recorded when the quantity was already right.
     */
    public int setAndReturnChange(Long storageId, Long offeringId, int target, Ref ref, MovementType type) {
        int counted = target;
        if (counted < 0) {
            throw ApiException.badRequest("Stock can't be below zero");
        }
        // Lock the row (or the gap where it will go) so nothing changes between reading and writing.
        Integer before = jdbc.sql("SELECT quantity FROM stock_levels WHERE storage_id = :s AND offering_id = :o FOR UPDATE")
                .param("s", storageId).param("o", offeringId).query(Integer.class).optional().orElse(0);
        jdbc.sql("""
                        INSERT INTO stock_levels (storage_id, offering_id, quantity) VALUES (:s, :o, :q) AS new
                        ON DUPLICATE KEY UPDATE quantity = new.quantity
                        """)
                .param("s", storageId).param("o", offeringId).param("q", counted).update();
        int change = counted - before;
        if (change != 0) {
            MovementType t = type != null ? type : change > 0 ? MovementType.ADJUSTMENT_IN : MovementType.ADJUSTMENT_OUT;
            record(storageId, offeringId, t, change, counted, ref);
        }
        return change;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int quantity(Long storageId, Long offeringId) {
        return jdbc.sql("SELECT quantity FROM stock_levels WHERE storage_id = :s AND offering_id = :o")
                .param("s", storageId).param("o", offeringId).query(Integer.class).optional().orElse(0);
    }

    /** Total quantity per entry across the given storages. Entries with no stock row are absent. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<Long, Integer> totals(Collection<Long> offeringIds, Collection<Long> storageIds) {
        Map<Long, Integer> totals = new HashMap<>();
        if (offeringIds.isEmpty() || storageIds.isEmpty()) {
            return totals;
        }
        jdbc.sql("""
                        SELECT offering_id, SUM(quantity) AS qty FROM stock_levels
                        WHERE offering_id IN (:o) AND storage_id IN (:s) GROUP BY offering_id
                        """)
                .param("o", offeringIds).param("s", storageIds)
                .query((rs, n) -> totals.put(rs.getLong("offering_id"), rs.getInt("qty")))
                .list();
        return totals;
    }

    /** Where an entry is stocked: every storage of every branch, including empty ones. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<LevelRow> levels(Long offeringId) {
        return jdbc.sql("""
                        SELECT b.id AS branch_id, b.name AS branch_name, s.id AS storage_id, s.name AS storage_name,
                               s.selling, s.active, COALESCE(l.quantity, 0) AS qty
                        FROM storages s JOIN branches b ON b.id = s.branch_id
                        LEFT JOIN stock_levels l ON l.storage_id = s.id AND l.offering_id = :o
                        WHERE b.active
                        ORDER BY b.name, s.selling DESC, s.name
                        """)
                .param("o", offeringId)
                .query((rs, n) -> new LevelRow(rs.getLong("branch_id"), rs.getString("branch_name"),
                        rs.getLong("storage_id"), rs.getString("storage_name"), rs.getBoolean("selling"),
                        rs.getBoolean("active"), rs.getInt("qty")))
                .list();
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int storageTotal(Long storageId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM stock_levels WHERE storage_id = :s")
                .param("s", storageId).query(Integer.class).single();
    }

    private void record(Long storageId, Long offeringId, MovementType type, int change, int balance, Ref ref) {
        jdbc.sql("""
                        INSERT INTO stock_movements
                            (offering_id, storage_id, type, reason, quantity, balance, user_id, order_id, transfer_id,
                             adjustment_id, goods_receipt_id, note, created_at)
                        VALUES (:o, :s, :type, :reason, :q, :b, :u, :order, :transfer, :adj, :grn, :note, :at)
                        """)
                .param("o", offeringId).param("s", storageId).param("type", type.name())
                .param("q", change).param("b", balance)
                .param("u", ref.userId()).param("order", ref.orderId()).param("transfer", ref.transferId())
                .param("note", ref.note()).param("at", Timestamp.from(Instant.now()))
                .param("reason", ref.reason() == null ? null : ref.reason().name()).param("adj", ref.adjustmentId()).param("grn", ref.receiptId())
                .update();
    }

    private String offeringName(Long id) {
        return jdbc.sql("SELECT name FROM offerings WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String storageName(Long id) {
        return jdbc.sql("SELECT CONCAT(b.name, ' / ', s.name) FROM storages s JOIN branches b ON b.id = s.branch_id WHERE s.id = :id")
                .param("id", id).query(String.class).single();
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw ApiException.badRequest("Enter an amount above zero");
        }
    }
}
