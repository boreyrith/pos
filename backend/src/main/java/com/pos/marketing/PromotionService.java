package com.pos.marketing;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.common.ApiException;
import com.pos.customer.Customer;
import com.pos.customer.CustomerRepository;
import com.pos.customer.CustomerService;
import com.pos.inventory.BranchContext;
import com.pos.marketing.PromotionDtos.AppliedDto;
import com.pos.marketing.PromotionDtos.CouponBatchRequest;
import com.pos.marketing.PromotionDtos.CouponDto;
import com.pos.marketing.PromotionDtos.CouponRequest;
import com.pos.marketing.PromotionDtos.CouponResult;
import com.pos.marketing.PromotionDtos.CouponUpdate;
import com.pos.marketing.PromotionDtos.EvaluateRequest;
import com.pos.marketing.PromotionDtos.EvaluateResponse;
import com.pos.marketing.PromotionDtos.LinePromo;
import com.pos.marketing.PromotionDtos.PromotionDto;
import com.pos.marketing.PromotionDtos.PromotionRequest;
import com.pos.marketing.PromotionDtos.Status;
import com.pos.marketing.PromotionEngine.Item;
import com.pos.marketing.PromotionEngine.Rule;
import com.pos.sales.DiscountType;
import com.pos.sales.Order;
import com.pos.sales.OrderItem;
import com.pos.sales.OrderPromotion;
import com.pos.sales.SalePromotions;
import com.pos.sales.SalesDtos.CartLine;
import com.pos.settings.SettingsService;

/**
 * Promotions and coupon codes: managing them, deciding which are running for a sale, pricing a
 * cart with them, and counting their use. A sale reserves its use when it is created (so a KHQR
 * order waiting for payment holds its coupon) and gives it back if it is cancelled or expires;
 * refunds keep it used.
 */
@Service
public class PromotionService implements SalePromotions {

    /** No 0/O, 1/I/L: codes are read aloud and typed from paper. */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PromotionRepository promotions;
    private final CouponCodeRepository coupons;
    private final OfferingRepository offerings;
    private final CustomerService customerService;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final BranchContext branches;
    private final SettingsService settings;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public PromotionService(PromotionRepository promotions, CouponCodeRepository coupons, OfferingRepository offerings,
                            CustomerService customerService, CustomerRepository customers, UserRepository users,
                            BranchContext branches, SettingsService settings, AuditService audit, JdbcClient jdbc) {
        this.promotions = promotions;
        this.coupons = coupons;
        this.offerings = offerings;
        this.customerService = customerService;
        this.customers = customers;
        this.users = users;
        this.branches = branches;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- pricing a sale

    /** What promotions would take off a cart on the sell screen; nothing is reserved. */
    @Transactional(readOnly = true)
    public EvaluateResponse evaluate(EvaluateRequest req, AuthUser cashier) {
        Map<Long, Offering> byId = offerings.findAllById(req.items().stream().map(CartLine::offeringId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Offering::getId, Function.identity()));
        List<Item> items = new ArrayList<>();
        for (CartLine l : req.items()) {
            Offering o = byId.get(l.offeringId());
            if (o == null) {
                throw ApiException.badRequest("Something in the cart is no longer for sale (id " + l.offeringId() + ")");
            }
            boolean changed = l.unitPrice() != null && l.unitPrice().compareTo(o.getPrice()) != 0;
            items.add(new Item(o.getId(), categoryOf(o), changed ? l.unitPrice() : o.getPrice(), l.quantity(), l.hours(), !changed,
                    l.discountValue() != null && l.discountValue().signum() > 0 ? l.discountType() : null, l.discountValue()));
        }
        Customer customer = customerService.forSale(req.customerId());
        Long branchId = branches.sellingStorage(cashier).getBranch().getId();
        Priced priced = price(items, branchId, customer, blankToNull(req.couponCode()));
        var r = priced.result();
        return new EvaluateResponse(
                r.lines().stream().map(l -> new LinePromo(l.promoDiscount(), l.promotionId(), l.promotionName())).toList(),
                r.orderDiscount(),
                r.applied().stream().map(a -> new AppliedDto(a.promotionId(), a.name(), a.code(), a.kind(), a.amount())).toList(),
                priced.couponResult());
    }

    /**
     * Prices a sale being created: sets each line's promotion, records what each promotion took on
     * the order, and reserves their use (locked, so two tills can't take the last one). Returns the
     * order promotion for {@code PricingCalculator}. A coupon that can't be used is refused.
     */
    @Transactional
    public BigDecimal applyToSale(Order order, String couponCode) {
        List<Item> items = new ArrayList<>();
        for (OrderItem i : order.getItems()) {
            items.add(new Item(i.getOffering().getId(), categoryOf(i.getOffering()), i.getUnitPrice(), i.getQuantity(), i.getHours(),
                    !i.isPriceOverridden(), i.getItemDiscountType(), i.getItemDiscountValue()));
        }
        Priced priced = price(items, order.getBranch().getId(), order.getCustomer(), blankToNull(couponCode));
        if (priced.couponResult() != null && !priced.couponResult().applied()) {
            throw ApiException.badRequest("Coupon " + priced.couponResult().code() + ": " + priced.couponResult().message());
        }
        var r = priced.result();
        for (int n = 0; n < order.getItems().size(); n++) {
            OrderItem i = order.getItems().get(n);
            i.setPromoDiscount(r.lines().get(n).promoDiscount());
            i.setPromotionName(r.lines().get(n).promotionName());
        }
        reserve(r.applied(), order.getCustomer());
        r.applied().forEach(a -> order.addPromotion(a.promotionId(), a.couponCodeId(), a.code(), a.name(), a.kind().name(), a.amount()));
        order.setCouponCode(priced.couponResult() == null ? null : priced.couponResult().code());
        return r.orderDiscount();
    }

    /** Gives back the use of a sale's promotions and coupon when it is cancelled or expires unpaid. */
    @Transactional
    public void release(Order order) {
        if (order.getPromotions().isEmpty()) {
            return;
        }
        for (Promotion p : promotions.lockAll(order.getPromotions().stream().map(OrderPromotion::getPromotionId).toList())) {
            p.setUsedCount(Math.max(0, p.getUsedCount() - 1));
        }
        order.getPromotions().stream().map(OrderPromotion::getCouponCodeId).filter(java.util.Objects::nonNull)
                .forEach(id -> coupons.lockById(id).ifPresent(c -> c.setUsedCount(Math.max(0, c.getUsedCount() - 1))));
    }

    private record Priced(PromotionEngine.Result result, CouponResult couponResult) {
    }

    private Priced price(List<Item> items, Long branchId, Customer customer, String code) {
        ZonedDateTime now = ZonedDateTime.now(settings.current().timezone());
        List<Promotion> candidates = promotions.findByActiveTrueAndRequiresCodeFalse();
        CouponCode coupon = code == null ? null : coupons.findByCode(code).orElse(null);
        Set<Long> limited = new HashSet<>();
        candidates.stream().filter(p -> p.getPerCustomerLimit() != null).forEach(p -> limited.add(p.getId()));
        if (coupon != null && coupon.getPromotion().getPerCustomerLimit() != null) {
            limited.add(coupon.getPromotion().getId());
        }
        Map<Long, Long> usedByCustomer = usesBy(customer, limited);

        List<Rule> running = candidates.stream().filter(p -> notRunning(p, now, branchId, customer, usedByCustomer) == null)
                .map(p -> rule(p, null)).toList();
        Rule couponRule = null;
        CouponResult couponResult = null;
        if (code != null) {
            String problem = coupon == null ? "This code doesn't exist" : couponProblem(coupon, now, branchId, customer, usedByCustomer);
            if (problem == null) {
                couponRule = rule(coupon.getPromotion(), coupon);
            } else {
                couponResult = new CouponResult(code.toUpperCase(), false, problem);
            }
        }
        var result = PromotionEngine.apply(items, running, couponRule);
        if (couponRule != null) {
            couponResult = new CouponResult(coupon.getCode(), result.couponProblem() == null, result.couponProblem());
        }
        return new Priced(result, couponResult);
    }

    private static Rule rule(Promotion p, CouponCode c) {
        return new Rule(p.getId(), p.getName(), p.getKind(), p.getDiscountType(), p.getDiscountValue(), p.getScope(),
                Set.copyOf(p.getCategoryIds()), Set.copyOf(p.getOfferingIds()), p.getBuyQty(), p.getGetQty(), p.getMinSpend(),
                p.getMaxDiscount(), p.getPriority(), c == null ? null : c.getId(), c == null ? null : c.getCode());
    }

    /** Why a coupon can't be used now, or null. */
    private static String couponProblem(CouponCode c, ZonedDateTime now, Long branchId, Customer customer, Map<Long, Long> usedByCustomer) {
        if (!c.isActive()) {
            return "This code has been turned off";
        }
        if (c.getExpiresOn() != null && now.toLocalDate().isAfter(c.getExpiresOn())) {
            return "This code expired on " + c.getExpiresOn();
        }
        if (c.usedUp()) {
            return c.getMaxUses() == 1 ? "This code has already been used" : "This code has been used up";
        }
        if (c.getCustomer() != null && (customer == null || !c.getCustomer().getId().equals(customer.getId()))) {
            return "This code belongs to " + c.getCustomer().getName() + ": pick them as the customer";
        }
        return notRunning(c.getPromotion(), now, branchId, customer, usedByCustomer);
    }

    /** Why a promotion isn't running for this sale now, or null when it is. */
    private static String notRunning(Promotion p, ZonedDateTime now, Long branchId, Customer customer, Map<Long, Long> usedByCustomer) {
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (!p.isActive()) {
            return "This promotion is paused";
        }
        if (p.getStartsOn() != null && today.isBefore(p.getStartsOn())) {
            return "This promotion starts on " + p.getStartsOn();
        }
        if (p.getEndsOn() != null && today.isAfter(p.getEndsOn())) {
            return "This promotion ended on " + p.getEndsOn();
        }
        if (p.getDaysOfWeek() != null && !days(p.getDaysOfWeek()).contains(now.getDayOfWeek().getValue())) {
            return "This promotion doesn't run today";
        }
        if (p.getTimeFrom() != null && p.getTimeTo() != null && !within(time, p.getTimeFrom(), p.getTimeTo())) {
            return "This promotion runs from " + p.getTimeFrom() + " to " + p.getTimeTo();
        }
        if (!p.getBranchIds().isEmpty() && !p.getBranchIds().contains(branchId)) {
            return "This promotion doesn't run at this branch";
        }
        boolean walkIn = customer == null || customer.isDefaultCustomer();
        if ((p.isCustomersOnly() || p.getPerCustomerLimit() != null || p.getMinTier() != null) && walkIn) {
            return "This promotion is for customers on file: pick the customer first";
        }
        if (p.getMinTier() != null && (customer.getTier() == null || !customer.getTier().atLeast(p.getMinTier()))) {
            return "This promotion is for " + tierName(p.getMinTier()) + " customers and above";
        }
        if (p.getUsageLimit() != null && p.getUsedCount() >= p.getUsageLimit()) {
            return "This promotion has been used up";
        }
        if (p.getPerCustomerLimit() != null && usedByCustomer.getOrDefault(p.getId(), 0L) >= p.getPerCustomerLimit()) {
            return "This customer has already used this promotion" + (p.getPerCustomerLimit() == 1 ? "" : " " + p.getPerCustomerLimit() + " times");
        }
        return null;
    }

    private static String tierName(com.pos.customer.Tier t) {
        return t.name().charAt(0) + t.name().substring(1).toLowerCase();
    }

    /** From-to in the store's time; a range past midnight (22:00-02:00) wraps. */
    private static boolean within(LocalTime t, LocalTime from, LocalTime to) {
        return from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
    }

    /** Sales a customer made with each promotion, not counting cancelled ones. */
    private Map<Long, Long> usesBy(Customer customer, Collection<Long> promotionIds) {
        if (customer == null || customer.isDefaultCustomer() || promotionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> out = new HashMap<>();
        jdbc.sql("""
                        SELECT op.promotion_id, COUNT(DISTINCT op.order_id) AS n
                        FROM order_promotions op JOIN orders o ON o.id = op.order_id
                        WHERE o.customer_id = :customer AND o.status <> 'CANCELLED' AND op.promotion_id IN (:ids)
                        GROUP BY op.promotion_id
                        """)
                .param("customer", customer.getId()).param("ids", promotionIds)
                .query((rs, n) -> out.put(rs.getLong("promotion_id"), rs.getLong("n"))).list();
        return out;
    }

    /** Counts a sale's use of its promotions and coupon, re-checking limits under lock. */
    private void reserve(List<PromotionEngine.Applied> applied, Customer customer) {
        if (applied.isEmpty()) {
            return;
        }
        Map<Long, Promotion> locked = promotions.lockAll(applied.stream().map(PromotionEngine.Applied::promotionId).toList()).stream()
                .collect(Collectors.toMap(Promotion::getId, Function.identity()));
        Map<Long, Long> used = usesBy(customer, locked.values().stream().filter(p -> p.getPerCustomerLimit() != null)
                .map(Promotion::getId).toList());
        for (var a : applied) {
            Promotion p = locked.get(a.promotionId());
            boolean gone = (p.getUsageLimit() != null && p.getUsedCount() >= p.getUsageLimit())
                    || (p.getPerCustomerLimit() != null && used.getOrDefault(p.getId(), 0L) >= p.getPerCustomerLimit());
            if (gone) {
                throw new ApiException(HttpStatus.CONFLICT, "The promotion \"" + p.getName() + "\" was just used up. Check the cart and charge again.",
                        "PRICES_CHANGED");
            }
            p.setUsedCount(p.getUsedCount() + 1);
            if (a.couponCodeId() != null) {
                CouponCode c = coupons.lockById(a.couponCodeId()).orElseThrow();
                if (c.usedUp()) {
                    throw new ApiException(HttpStatus.CONFLICT, "Coupon " + c.getCode() + " was just used. Check the cart and charge again.",
                            "PRICES_CHANGED");
                }
                c.setUsedCount(c.getUsedCount() + 1);
            }
        }
    }

    private static Long categoryOf(Offering o) {
        return o.getCategory() == null ? null : o.getCategory().getId();
    }

    // ---------------------------------------------------------------- managing promotions

    @Transactional(readOnly = true)
    public List<PromotionDto> list() {
        List<Promotion> all = promotions.findAllByOrderByActiveDescCreatedAtDesc();
        Map<Long, Stats> stats = stats(all.stream().map(Promotion::getId).toList());
        Map<Long, Long> codeCounts = codeCounts();
        ZonedDateTime now = ZonedDateTime.now(settings.current().timezone());
        return all.stream().map(p -> dto(p, stats.get(p.getId()), codeCounts.getOrDefault(p.getId(), 0L), now)).toList();
    }

    @Transactional(readOnly = true)
    public PromotionDto get(Long id) {
        Promotion p = find(id);
        return dto(p, stats(List.of(id)).get(id), codeCounts().getOrDefault(id, 0L), ZonedDateTime.now(settings.current().timezone()));
    }

    @Transactional
    public PromotionDto create(PromotionRequest req, AuthUser user) {
        validate(req);
        Promotion p = new Promotion();
        apply(p, req);
        p.setCreatedBy(users.getReferenceById(user.id()));
        promotions.saveAndFlush(p);
        audit.log(AuditAction.PROMOTION_CREATED, "Promotion", p.getId(), "Added promotion " + p.getName() + " (" + describe(p) + ")");
        return get(p.getId());
    }

    @Transactional
    public PromotionDto update(Long id, PromotionRequest req) {
        validate(req);
        Promotion p = find(id);
        if (p.getKind() != req.kind() && p.getUsedCount() > 0) {
            throw ApiException.badRequest("This promotion has been used, so its kind can't change: add a new promotion instead");
        }
        String before = describe(p);
        Changes changes = new Changes().add("Name", p.getName(), req.name()).add("Active", p.isActive(), req.isActive());
        boolean wasActive = p.isActive();
        apply(p, req);
        p.setUpdatedAt(Instant.now());
        changes.add("Deal", before, describe(p));
        audit.log(AuditAction.PROMOTION_UPDATED, "Promotion", p.getId(),
                (wasActive != p.isActive() ? (p.isActive() ? "Resumed" : "Paused") : "Changed") + " promotion " + p.getName(), changes);
        return get(id);
    }

    /** Only a promotion no sale has used can be deleted; pause it otherwise. */
    @Transactional
    public void delete(Long id) {
        Promotion p = find(id);
        Long sales = jdbc.sql("SELECT COUNT(*) FROM order_promotions WHERE promotion_id = :id").param("id", id).query(Long.class).single();
        if (sales > 0) {
            throw ApiException.conflict("This promotion has been used on " + sales + " sale(s) and is kept for the records: pause it instead");
        }
        jdbc.sql("DELETE FROM coupon_codes WHERE promotion_id = :id").param("id", id).update();
        promotions.delete(p);
        audit.log(AuditAction.PROMOTION_DELETED, "Promotion", id, "Deleted promotion " + p.getName());
    }

    private void validate(PromotionRequest req) {
        if (!PromotionDtos.percentOk(req.discountType(), req.discountValue())) {
            throw ApiException.badRequest("A % discount can't be more than 100%");
        }
        if (req.kind() == PromotionKind.BUY_X_GET_Y) {
            if (req.buyQty() == null || req.getQty() == null) {
                throw ApiException.badRequest("Say how many to buy and how many they get");
            }
            if (req.discountType() != DiscountType.PERCENT) {
                throw ApiException.badRequest("Buy X get Y takes a % off the units they get (100% = free)");
            }
        }
        if (req.kind() == PromotionKind.ORDER_DISCOUNT && req.scopeOrAll() != PromotionScope.ALL) {
            throw ApiException.badRequest("An order discount applies to the whole order");
        }
        if (req.scopeOrAll() == PromotionScope.CATEGORIES && (req.categoryIds() == null || req.categoryIds().isEmpty())) {
            throw ApiException.badRequest("Pick at least one category");
        }
        if (req.scopeOrAll() == PromotionScope.OFFERINGS && (req.offeringIds() == null || req.offeringIds().isEmpty())) {
            throw ApiException.badRequest("Pick at least one product, service or item");
        }
        if (req.startsOn() != null && req.endsOn() != null && req.endsOn().isBefore(req.startsOn())) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        if ((req.timeFrom() == null) != (req.timeTo() == null) || (req.timeFrom() != null && req.timeFrom().equals(req.timeTo()))) {
            throw ApiException.badRequest("Give both a start and an end time, or neither");
        }
        if (req.maxDiscount() != null && !(req.kind() == PromotionKind.ORDER_DISCOUNT && req.discountType() == DiscountType.PERCENT)) {
            throw ApiException.badRequest("A maximum discount only applies to a % off the order");
        }
    }

    private void apply(Promotion p, PromotionRequest req) {
        p.setName(req.name().trim());
        p.setDescription(blankToNull(req.description()));
        p.setKind(req.kind());
        p.setDiscountType(req.discountType());
        p.setDiscountValue(req.discountValue());
        p.setScope(req.scopeOrAll());
        p.getCategoryIds().clear();
        p.getOfferingIds().clear();
        if (req.scopeOrAll() == PromotionScope.CATEGORIES) {
            p.getCategoryIds().addAll(req.categoryIds());
        }
        if (req.scopeOrAll() == PromotionScope.OFFERINGS) {
            p.getOfferingIds().addAll(req.offeringIds());
        }
        boolean bxgy = req.kind() == PromotionKind.BUY_X_GET_Y;
        p.setBuyQty(bxgy ? req.buyQty() : null);
        p.setGetQty(bxgy ? req.getQty() : null);
        p.setMinSpend(req.minSpend());
        p.setMaxDiscount(req.maxDiscount());
        p.setStartsOn(req.startsOn());
        p.setEndsOn(req.endsOn());
        p.setDaysOfWeek(req.daysOfWeek() == null || req.daysOfWeek().isEmpty() || req.daysOfWeek().size() == 7 ? null
                : req.daysOfWeek().stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        p.setTimeFrom(req.timeFrom());
        p.setTimeTo(req.timeTo());
        p.setRequiresCode(req.needsCode());
        p.setCustomersOnly(req.forCustomersOnly());
        p.setMinTier(req.minTier());
        p.setUsageLimit(req.usageLimit());
        p.setPerCustomerLimit(req.perCustomerLimit());
        p.setPriority(req.priorityOrZero());
        p.setActive(req.isActive());
        p.getBranchIds().clear();
        if (req.branchIds() != null) {
            p.getBranchIds().addAll(req.branchIds());
        }
    }

    /** "20% off Diapers", "Buy 2 get 1 free", "$5.00 off orders over 50.00" for audit entries. */
    private static String describe(Promotion p) {
        String value = p.getDiscountType() == DiscountType.PERCENT ? p.getDiscountValue().stripTrailingZeros().toPlainString() + "%"
                : p.getDiscountValue().toPlainString();
        String what = switch (p.getKind()) {
            case ITEM_DISCOUNT -> value + " off " + (p.getDiscountType() == DiscountType.AMOUNT ? "each unit" : "items");
            case BUY_X_GET_Y -> "buy " + p.getBuyQty() + " get " + p.getGetQty() + " " + (p.getDiscountValue().compareTo(BigDecimal.valueOf(100)) == 0
                    ? "free" : value + " off");
            case ORDER_DISCOUNT -> value + " off the order";
        };
        return what + (p.getMinSpend() == null ? "" : ", min spend " + p.getMinSpend()) + (p.isRequiresCode() ? ", with a code" : "")
                + (p.getMinTier() == null ? "" : ", " + tierName(p.getMinTier()) + " and above");
    }

    private record Stats(BigDecimal given, long orders) {
    }

    private Map<Long, Stats> stats(List<Long> ids) {
        Map<Long, Stats> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT op.promotion_id, COALESCE(SUM(op.amount), 0) AS given, COUNT(DISTINCT op.order_id) AS orders
                        FROM order_promotions op JOIN orders o ON o.id = op.order_id
                        WHERE o.status <> 'CANCELLED' AND op.promotion_id IN (:ids)
                        GROUP BY op.promotion_id
                        """)
                .param("ids", ids)
                .query((rs, n) -> out.put(rs.getLong("promotion_id"), new Stats(rs.getBigDecimal("given"), rs.getLong("orders")))).list();
        return out;
    }

    private Map<Long, Long> codeCounts() {
        Map<Long, Long> out = new HashMap<>();
        jdbc.sql("SELECT promotion_id, COUNT(*) AS n FROM coupon_codes GROUP BY promotion_id")
                .query((rs, n) -> out.put(rs.getLong("promotion_id"), rs.getLong("n"))).list();
        return out;
    }

    private PromotionDto dto(Promotion p, Stats stats, long codes, ZonedDateTime now) {
        Status status;
        if (!p.isActive()) {
            status = Status.PAUSED;
        } else if (p.getEndsOn() != null && now.toLocalDate().isAfter(p.getEndsOn())) {
            status = Status.ENDED;
        } else if (p.getUsageLimit() != null && p.getUsedCount() >= p.getUsageLimit()) {
            status = Status.USED_UP;
        } else if (p.getStartsOn() != null && now.toLocalDate().isBefore(p.getStartsOn())) {
            status = Status.SCHEDULED;
        } else {
            status = Status.RUNNING;
        }
        return new PromotionDto(p.getId(), p.getName(), p.getDescription(), p.getKind(), p.getDiscountType(), p.getDiscountValue(),
                p.getScope(), Set.copyOf(p.getCategoryIds()), Set.copyOf(p.getOfferingIds()), p.getBuyQty(), p.getGetQty(),
                p.getMinSpend(), p.getMaxDiscount(), p.getStartsOn(), p.getEndsOn(),
                p.getDaysOfWeek() == null ? List.of() : days(p.getDaysOfWeek()), p.getTimeFrom(), p.getTimeTo(), p.isRequiresCode(),
                p.isCustomersOnly(), p.getMinTier(), p.getUsageLimit(), p.getPerCustomerLimit(), p.getUsedCount(), p.getPriority(), p.isActive(),
                Set.copyOf(p.getBranchIds()), status, stats == null ? BigDecimal.ZERO.setScale(2) : stats.given(),
                stats == null ? 0 : stats.orders(), codes, p.getCreatedBy().getFullName(), p.getCreatedAt());
    }

    private static List<Integer> days(String csv) {
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    }

    private Promotion find(Long id) {
        return promotions.findById(id).orElseThrow(() -> ApiException.notFound("Promotion"));
    }

    // ---------------------------------------------------------------- coupon codes

    @Transactional(readOnly = true)
    public List<CouponDto> codes(Long promotionId) {
        find(promotionId);
        return coupons.findByPromotion(promotionId).stream().map(CouponDto::from).toList();
    }

    @Transactional
    public CouponDto addCode(Long promotionId, CouponRequest req, AuthUser user) {
        Promotion p = requireCodePromotion(promotionId);
        String code = req.code().trim().toUpperCase();
        if (coupons.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("The code " + code + " is already taken");
        }
        CouponCode c = newCode(p, code, req.maxUses(), req.expiresOn(), user);
        if (req.customerId() != null) {
            Customer customer = customers.findById(req.customerId()).orElseThrow(() -> ApiException.badRequest("That customer doesn't exist"));
            if (customer.isDefaultCustomer()) {
                throw ApiException.badRequest("A code can't belong to the Walk-in Customer");
            }
            c.setCustomer(customer);
        }
        coupons.save(c);
        audit.log(AuditAction.COUPONS_CREATED, "Promotion", p.getId(), "Added coupon code " + code + " to " + p.getName()
                + (c.getMaxUses() == null ? "" : " (up to " + c.getMaxUses() + " use" + (c.getMaxUses() == 1 ? "" : "s") + ")"));
        return CouponDto.from(c);
    }

    /** Generates unique codes like FLYER-7K3M9Q, single use unless {@code maxUses} says otherwise. */
    @Transactional
    public List<CouponDto> generate(Long promotionId, CouponBatchRequest req, AuthUser user) {
        Promotion p = requireCodePromotion(promotionId);
        String prefix = req.prefix() == null || req.prefix().isBlank() ? "" : req.prefix().trim().toUpperCase() + "-";
        List<CouponCode> made = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (made.size() < req.count()) {
            String code = prefix + random(8);
            if (seen.add(code) && !coupons.existsByCodeIgnoreCase(code)) {
                made.add(newCode(p, code, req.maxUses() == null ? 1 : req.maxUses(), req.expiresOn(), user));
            }
        }
        coupons.saveAll(made);
        audit.log(AuditAction.COUPONS_CREATED, "Promotion", p.getId(), "Generated " + made.size() + " coupon codes for " + p.getName());
        return made.stream().map(CouponDto::from).toList();
    }

    /** Codes for one customer each (campaigns). */
    @Transactional
    public CouponCode issue(Promotion p, Customer customer, String prefix, LocalDate expiresOn, AuthUser user) {
        String code;
        do {
            code = (prefix == null || prefix.isBlank() ? "" : prefix.toUpperCase() + "-") + random(8);
        } while (coupons.existsByCodeIgnoreCase(code));
        CouponCode c = newCode(p, code, 1, expiresOn, user);
        c.setCustomer(customer);
        return coupons.save(c);
    }

    @Transactional
    public CouponDto updateCode(Long promotionId, Long codeId, CouponUpdate req) {
        CouponCode c = coupons.findById(codeId).filter(x -> x.getPromotion().getId().equals(promotionId))
                .orElseThrow(() -> ApiException.notFound("Coupon code"));
        Changes changes = new Changes().add("Active", c.isActive(), req.active()).add("Expires", c.getExpiresOn(), req.expiresOn());
        c.setActive(req.active());
        c.setExpiresOn(req.expiresOn());
        if (!changes.isEmpty()) {
            audit.log(AuditAction.COUPON_UPDATED, "Promotion", promotionId, "Changed coupon code " + c.getCode(), changes);
        }
        return CouponDto.from(c);
    }

    private Promotion requireCodePromotion(Long promotionId) {
        Promotion p = find(promotionId);
        if (!p.isRequiresCode()) {
            throw ApiException.badRequest(p.getName() + " runs without a code: turn on \"Needs a coupon code\" first");
        }
        return p;
    }

    private CouponCode newCode(Promotion p, String code, Integer maxUses, LocalDate expiresOn, AuthUser user) {
        CouponCode c = new CouponCode();
        c.setPromotion(p);
        c.setCode(code);
        c.setMaxUses(maxUses);
        c.setExpiresOn(expiresOn);
        c.setCreatedBy(users.getReferenceById(user.id()));
        return c;
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
