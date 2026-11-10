package com.pos.loyalty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.common.Money;
import com.pos.customer.Customer;
import com.pos.customer.CustomerRepository;
import com.pos.customer.Tier;
import com.pos.loyalty.LoyaltyTransaction.Type;
import com.pos.sales.CustomerRewards;
import com.pos.sales.Order;
import com.pos.sales.OrderReturn;
import com.pos.sales.OrderStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Loyalty points. Customers on file earn on what they pay (once the sale is paid) and redeem points
 * as the last discount before tax. Every change goes through here, under a lock on the customer, and
 * is recorded with the balance after it. Refunds take back earned points and give back used ones in
 * proportion to what is refunded; the last refund settles the rest exactly.
 */
@Service
public class LoyaltyService implements CustomerRewards {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LoyaltySettingsRepository settingsRepo;
    private final LoyaltyTierRepository tiers;
    private final LoyaltyTransactionRepository transactions;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public LoyaltyService(LoyaltySettingsRepository settingsRepo, LoyaltyTierRepository tiers, LoyaltyTransactionRepository transactions,
                          CustomerRepository customers, UserRepository users, AuditService audit, JdbcClient jdbc) {
        this.settingsRepo = settingsRepo;
        this.tiers = tiers;
        this.transactions = transactions;
        this.customers = customers;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** The rules as the sell screen and the marketing page need them. */
    /**
     * @param tierMonths months of spending that count towards a tier (null = all time)
     * @param tiers      Bronze to Platinum: the spend that reaches each and its points multiplier
     */
    public record Rules(boolean enabled, BigDecimal pointsPerUnit, BigDecimal pointValue, int minRedeem, BigDecimal maxRedeemPct,
                        String updatedBy, Instant updatedAt, Integer tierMonths, List<TierRule> tiers) {
    }

    public record TierRule(@NotNull Tier code, @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minSpend,
                           @NotNull @DecimalMin(value = "0.01") @DecimalMax("10") BigDecimal pointsMultiplier) {
    }

    public record RulesRequest(boolean enabled,
                               @NotNull @DecimalMin("0")
                               @Digits(integer = 6, fraction = 4) BigDecimal pointsPerUnit,
                               @NotNull @DecimalMin(value = "0", inclusive = false)
                               @Digits(integer = 6, fraction = 4) BigDecimal pointValue,
                               @Min(1) int minRedeem,
                               @NotNull @DecimalMin(value = "0", inclusive = false)
                               @DecimalMax("100") BigDecimal maxRedeemPct,
                               @Min(1) @Max(120) Integer tierMonths,
                               /** Left out, the tiers stay as they are. */
                               @Size(min = 4, max = 4) List<@Valid TierRule> tiers) {
    }

    public record TransactionDto(Long id, Long customerId, String customerName, Type type, int points, int balanceAfter, Long orderId,
                                 Long returnId, String note, String createdBy, Instant createdAt) {
        static TransactionDto from(LoyaltyTransaction t) {
            return new TransactionDto(t.getId(), t.getCustomer().getId(), t.getCustomer().getName(), t.getType(), t.getPoints(),
                    t.getBalanceAfter(), t.getOrderId(), t.getReturnId(), t.getNote(),
                    t.getCreatedBy() == null ? null : t.getCreatedBy().getFullName(), t.getCreatedAt());
        }
    }

    public record AdjustRequest(@NotNull Integer points,
                                @NotBlank @Size(min = 3, max = 255) String note) {
    }

    /** A customer's points, what they are worth now, and their tier. */
    public record Balance(Long customerId, int points, BigDecimal value, Tier tier) {
    }

    /**
     * @param outstanding points customers hold now (not counting negative balances)
     * @param liability   what those points are worth if all redeemed
     */
    /** {@code tierCounts}: customers on file in each tier. */
    public record Overview(Rules rules, long members, long outstanding, BigDecimal liability, long earned30, long redeemed30,
                           List<Member> top, Map<Tier, Long> tierCounts) {
    }

    public record Member(Long customerId, String code, String name, String phone, int points, Tier tier) {
    }

    @Transactional(readOnly = true)
    public Rules rules() {
        LoyaltySettings s = settings();
        return new Rules(s.isEnabled(), s.getPointsPerUnit(), s.getPointValue(), s.getMinRedeem(), s.getMaxRedeemPct(),
                s.getUpdatedBy() == null ? null : s.getUpdatedBy().getFullName(), s.getUpdatedAt(), s.getTierMonths(),
                tiers.findAllByOrderByRankOrderAsc().stream().map(t -> new TierRule(t.getCode(), t.getMinSpend(), t.getPointsMultiplier())).toList());
    }

    @Transactional
    public Rules updateRules(RulesRequest req, AuthUser user) {
        LoyaltySettings s = settings();
        Changes changes = new Changes()
                .add("On", s.isEnabled(), req.enabled())
                .add("Points per 1.00", s.getPointsPerUnit().stripTrailingZeros().toPlainString(), req.pointsPerUnit().stripTrailingZeros().toPlainString())
                .add("Point value", s.getPointValue().stripTrailingZeros().toPlainString(), req.pointValue().stripTrailingZeros().toPlainString())
                .add("Minimum to use", s.getMinRedeem(), req.minRedeem())
                .add("Most of a sale (%)", s.getMaxRedeemPct().stripTrailingZeros().toPlainString(), req.maxRedeemPct().stripTrailingZeros().toPlainString());
        boolean tiersChanged = !java.util.Objects.equals(s.getTierMonths(), req.tierMonths());
        changes.add("Tier months", s.getTierMonths() == null ? "all time" : s.getTierMonths(), req.tierMonths() == null ? "all time" : req.tierMonths());
        if (req.tiers() != null) {
            tiersChanged |= applyTiers(req.tiers(), changes);
        }
        s.setTierMonths(req.tierMonths());
        s.setEnabled(req.enabled());
        s.setPointsPerUnit(req.pointsPerUnit());
        s.setPointValue(req.pointValue());
        s.setMinRedeem(req.minRedeem());
        s.setMaxRedeemPct(req.maxRedeemPct());
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
        if (!changes.isEmpty()) {
            audit.log(AuditAction.LOYALTY_SETTINGS_UPDATED, "LoyaltySettings", 1L, "Changed the loyalty rules", changes);
        }
        if (tiersChanged) {
            settingsRepo.flush();
            tiers.flush();
            refreshAllTiers();
        }
        return rules();
    }

    /** Checks and saves the tier rules: Bronze from 0, each tier above needing more than the one below. */
    private boolean applyTiers(List<TierRule> rules, Changes changes) {
        List<TierRule> sorted = rules.stream().sorted(java.util.Comparator.comparing(TierRule::code)).toList();
        if (sorted.stream().map(TierRule::code).distinct().count() != 4) {
            throw ApiException.badRequest("Give each tier once: Bronze, Silver, Gold and Platinum");
        }
        if (sorted.getFirst().minSpend().signum() != 0) {
            throw ApiException.badRequest("Bronze is for every customer on file: its minimum spend is 0");
        }
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).minSpend().compareTo(sorted.get(i - 1).minSpend()) <= 0) {
                throw ApiException.badRequest("Each tier needs a higher spend than the one below it");
            }
        }
        boolean changed = false;
        for (TierRule r : sorted) {
            LoyaltyTier t = tiers.findById(r.code()).orElseThrow();
            String name = r.code().name().charAt(0) + r.code().name().substring(1).toLowerCase();
            if (t.getMinSpend().compareTo(r.minSpend()) != 0 || t.getPointsMultiplier().compareTo(r.pointsMultiplier()) != 0) {
                changes.add(name, t.getMinSpend().toPlainString() + " ×" + t.getPointsMultiplier().stripTrailingZeros().toPlainString(),
                        r.minSpend().toPlainString() + " ×" + r.pointsMultiplier().stripTrailingZeros().toPlainString());
                changed |= t.getMinSpend().compareTo(r.minSpend()) != 0;
                t.setMinSpend(r.minSpend());
                t.setPointsMultiplier(r.pointsMultiplier());
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- tiers

    /** Works a customer's tier out again from what they paid in the tier window (after a sale or a return). */
    @Transactional
    public void refreshTier(Customer customer) {
        if (customer.isDefaultCustomer()) {
            return;
        }
        Integer months = settings().getTierMonths();
        BigDecimal spent = jdbc.sql("SELECT COALESCE(SUM(total - refunded_total), 0) FROM orders WHERE customer_id = :id AND status IN "
                        + "('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')" + (months == null ? "" : " AND completed_at >= :since"))
                .param("id", customer.getId())
                .param("since", months == null ? null : java.sql.Timestamp.from(java.time.ZonedDateTime.now().minusMonths(months).toInstant()))
                .query(BigDecimal.class).single();
        Tier tier = Tier.BRONZE;
        for (LoyaltyTier t : tiers.findAllByOrderByRankOrderAsc()) {
            if (spent.compareTo(t.getMinSpend()) >= 0) {
                tier = t.getCode();
            }
        }
        customer.setTier(tier);
    }

    /** Works every customer's tier out again; returns how many moved. */
    @Transactional
    public int refreshAllTiers() {
        Integer months = settings().getTierMonths();
        return jdbc.sql("""
                        UPDATE customers c
                        LEFT JOIN (SELECT customer_id, SUM(total - refunded_total) AS spent FROM orders
                                   WHERE status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED') %s
                                   GROUP BY customer_id) s ON s.customer_id = c.id
                        SET c.tier = (SELECT t.code FROM loyalty_tiers t WHERE t.min_spend <= COALESCE(s.spent, 0)
                                      ORDER BY t.rank_order DESC LIMIT 1)
                        WHERE c.is_default = FALSE AND NOT (c.tier <=> (SELECT t.code FROM loyalty_tiers t
                                      WHERE t.min_spend <= COALESCE(s.spent, 0) ORDER BY t.rank_order DESC LIMIT 1))
                        """.formatted(months == null ? "" : "AND completed_at >= :since"))
                .param("since", months == null ? null : java.sql.Timestamp.from(java.time.ZonedDateTime.now().minusMonths(months).toInstant()))
                .update();
    }

    private BigDecimal multiplier(Tier tier) {
        if (tier == null) {
            return BigDecimal.ONE;
        }
        return tiers.findById(tier).map(LoyaltyTier::getPointsMultiplier).orElse(BigDecimal.ONE);
    }

    @Transactional(readOnly = true)
    public Balance balance(Long customerId) {
        Customer c = customers.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
        return new Balance(c.getId(), c.getPointsBalance(), worth(Math.max(0, c.getPointsBalance())), c.getTier());
    }

    /** What {@code points} are worth when redeemed. */
    @Transactional(readOnly = true)
    public BigDecimal worth(int points) {
        return Money.round(settings().getPointValue().multiply(BigDecimal.valueOf(points)));
    }

    // ---------------------------------------------------------------- at checkout and after

    /**
     * Checks points can be used on a sale and returns what they take off. {@code base} is what is
     * left of the sale after every other discount, before tax; points can pay at most the set % of it.
     */
    @Transactional(readOnly = true)
    public BigDecimal checkRedeem(Customer customer, int points, BigDecimal base) {
        LoyaltySettings s = settings();
        if (!s.isEnabled()) {
            throw ApiException.badRequest("Loyalty points are turned off");
        }
        if (customer.isDefaultCustomer()) {
            throw ApiException.badRequest("Pick the customer to use their points");
        }
        if (points < s.getMinRedeem()) {
            throw ApiException.badRequest("Use at least " + s.getMinRedeem() + " points at a time");
        }
        if (points > customer.getPointsBalance()) {
            throw ApiException.badRequest(customer.getName() + " has " + customer.getPointsBalance() + " points");
        }
        BigDecimal value = worth(points);
        int most = maxPoints(base, s);
        if (points > most) {
            throw ApiException.badRequest("At most " + most + " points can be used on this sale");
        }
        return value;
    }

    /** The most points that can pay for {@code base} (after discounts, before tax). */
    private static int maxPoints(BigDecimal base, LoyaltySettings s) {
        BigDecimal money = base.multiply(s.getMaxRedeemPct()).divide(HUNDRED, 10, RoundingMode.DOWN);
        return money.divide(s.getPointValue(), 0, RoundingMode.DOWN).intValue();
    }

    /** Takes the points used on a sale off the customer (after the sale is saved, so it can be linked). */
    @Transactional
    public void redeem(Order order, AuthUser cashier) {
        if (order.getPointsRedeemed() <= 0) {
            return;
        }
        Customer c = lock(order.getCustomer().getId());
        // Checked again under the lock: another till may have used them meanwhile.
        if (order.getPointsRedeemed() > c.getPointsBalance()) {
            throw ApiException.conflict(c.getName() + " now has " + c.getPointsBalance() + " points. Check the cart and charge again.");
        }
        record(c, Type.REDEEM, -order.getPointsRedeemed(), order.getId(), null, "Used on " + order.getOrderNumber(), cashier.id());
    }

    /** Points for a paid sale: on what was paid, rounded down. Customers on file only. */
    @Transactional
    public void earn(Order order) {
        LoyaltySettings s = settings();
        if (!s.isEnabled() || order.getCustomer().isDefaultCustomer() || order.getPointsEarned() > 0 || order.getTotal().signum() <= 0) {
            return;
        }
        // At the customer's tier before this sale counts towards it.
        int points = order.getTotal().multiply(s.getPointsPerUnit()).multiply(multiplier(order.getCustomer().getTier()))
                .setScale(0, RoundingMode.DOWN).intValue();
        if (points <= 0) {
            return;
        }
        Customer c = lock(order.getCustomer().getId());
        order.setPointsEarned(points);
        record(c, Type.EARN, points, order.getId(), null, "Earned on " + order.getOrderNumber(), null);
    }

    /** A sale cancelled or expired before payment gives back the points it used. */
    @Transactional
    public void cancelled(Order order) {
        if (order.getPointsRedeemed() <= 0) {
            return;
        }
        Customer c = lock(order.getCustomer().getId());
        record(c, Type.RESTORE, order.getPointsRedeemed(), order.getId(), null, "Given back: " + order.getOrderNumber() + " was not paid", null);
    }

    /**
     * A return takes back the points earned on what is refunded and gives back the points used on it,
     * in proportion to the money refunded so far; the last return settles what is left.
     */
    @Transactional
    public void returned(Order order, OrderReturn ret, AuthUser user) {
        if (order.getPointsEarned() <= 0 && order.getPointsRedeemed() <= 0) {
            return;
        }
        boolean all = order.getStatus() == OrderStatus.REFUNDED;
        int reversedBefore = order.getReturns().stream().filter(r -> r != ret).mapToInt(OrderReturn::getPointsReversed).sum();
        int restoredBefore = order.getReturns().stream().filter(r -> r != ret).mapToInt(OrderReturn::getPointsRestored).sum();
        int reverse = share(order.getPointsEarned(), order, all) - reversedBefore;
        int restore = share(order.getPointsRedeemed(), order, all) - restoredBefore;
        if (reverse <= 0 && restore <= 0) {
            return;
        }
        Customer c = lock(order.getCustomer().getId());
        if (reverse > 0) {
            ret.setPointsReversed(reverse);
            record(c, Type.REVERSE, -reverse, order.getId(), ret.getId(), "Taken back: return " + ret.getReturnNumber(), user.id());
        }
        if (restore > 0) {
            ret.setPointsRestored(restore);
            record(c, Type.RESTORE, restore, order.getId(), ret.getId(), "Given back: return " + ret.getReturnNumber(), user.id());
        }
    }

    /** Points for the money refunded so far: all of them once everything is back, else rounded down. */
    private static int share(int points, Order order, boolean all) {
        if (points <= 0) {
            return 0;
        }
        if (all) {
            return points;
        }
        if (order.getTotal().signum() <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(points).multiply(order.getRefundedTotal()).divide(order.getTotal(), 0, RoundingMode.DOWN).intValue();
    }

    // ---------------------------------------------------------------- managing

    @Transactional
    public Balance adjust(Long customerId, AdjustRequest req, AuthUser user) {
        if (req.points() == 0) {
            throw ApiException.badRequest("Enter points to add (or a minus number to take away)");
        }
        Customer c = lock(customerId);
        if (c.isDefaultCustomer()) {
            throw ApiException.badRequest("The Walk-in Customer doesn't collect points");
        }
        int before = c.getPointsBalance();
        record(c, Type.ADJUST, req.points(), null, null, req.note().trim(), user.id());
        audit.log(AuditAction.POINTS_ADJUSTED, "Customer", c.getId(), (req.points() > 0 ? "Added " : "Took away ") + Math.abs(req.points())
                + " points for " + c.getName() + ": " + req.note().trim(), new Changes().add("Points", before, c.getPointsBalance()));
        return balance(customerId);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> history(Long customerId, int limit) {
        return transactions.findByCustomer(customerId, PageRequest.of(0, limit)).map(TransactionDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> recent(int limit) {
        return transactions.recent(PageRequest.of(0, limit)).map(TransactionDto::from).toList();
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        LoyaltySettings s = settings();
        var totals = jdbc.sql("SELECT COUNT(*) AS members, COALESCE(SUM(points_balance), 0) AS points FROM customers WHERE points_balance > 0")
                .query((rs, n) -> new long[] {rs.getLong("members"), rs.getLong("points")}).single();
        var month = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN type = 'EARN' THEN points END), 0) AS earned,
                               COALESCE(-SUM(CASE WHEN type = 'REDEEM' THEN points END), 0) AS redeemed
                        FROM loyalty_transactions WHERE created_at >= :since
                        """)
                .param("since", java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(30))))
                .query((rs, n) -> new long[] {rs.getLong("earned"), rs.getLong("redeemed")}).single();
        List<Member> top = jdbc.sql("""
                        SELECT id, code, name, phone, points_balance, tier FROM customers
                        WHERE points_balance > 0 ORDER BY points_balance DESC, name LIMIT 10
                        """)
                .query((rs, n) -> new Member(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("phone"),
                        rs.getInt("points_balance"), rs.getString("tier") == null ? null : Tier.valueOf(rs.getString("tier")))).list();
        Map<Tier, Long> tierCounts = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) {
            tierCounts.put(t, 0L);
        }
        jdbc.sql("SELECT tier, COUNT(*) AS n FROM customers WHERE is_default = FALSE AND active = TRUE AND tier IS NOT NULL GROUP BY tier")
                .query((rs, n) -> tierCounts.put(Tier.valueOf(rs.getString("tier")), rs.getLong("n"))).list();
        return new Overview(rules(), totals[0], totals[1], Money.round(s.getPointValue().multiply(BigDecimal.valueOf(totals[1]))),
                month[0], month[1], top, tierCounts);
    }

    private void record(Customer c, Type type, int points, Long orderId, Long returnId, String note, Long userId) {
        c.setPointsBalance(c.getPointsBalance() + points);
        transactions.save(new LoyaltyTransaction(c, type, points, c.getPointsBalance(), orderId, returnId, note,
                userId == null ? null : users.getReferenceById(userId)));
    }

    private Customer lock(Long customerId) {
        return customers.lockById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    private LoyaltySettings settings() {
        return settingsRepo.findById(1L).orElseThrow(() -> new IllegalStateException("No loyalty settings row"));
    }
}
