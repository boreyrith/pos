package com.pos.hold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.access.Permission;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.common.ApiException;
import com.pos.config.AppProperties;
import com.pos.customer.Customer;
import com.pos.customer.CustomerService;
import com.pos.inventory.BranchContext;
import com.pos.inventory.BranchRepository;
import com.pos.sales.DiscountType;
import com.pos.sales.Order;
import com.pos.sales.PricingCalculator;
import com.pos.tax.TaxService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * Held (parked) sales. A cashier holds a cart; any till in the branch can resume it. Resuming locks
 * it to that cashier until they finish (it becomes an order), put it back, or the lock times out.
 * Old holds expire. Prices, discounts and price changes are checked again when the sale is paid,
 * against the person completing it; resuming lists what changed since the hold.
 */
@Service
public class HeldSaleService {

    public record HoldLine(@NotNull Long offeringId, @Min(1) @Max(10_000) int quantity,
                           @DecimalMin(value = "0", inclusive = false) @DecimalMax("999.99") @Digits(integer = 3, fraction = 2) BigDecimal hours,
                           Long staffId,
                           @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
                           @Size(max = 255) String priceReason, DiscountType discountType,
                           @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountValue) {
    }

    public record HoldRequest(@Size(max = 100) String label, Long customerId, @Size(max = 255) String note,
                              DiscountType discountType, @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
                              @NotEmpty @Size(max = 200) List<@Valid HoldLine> lines) {
    }

    /** One stored line; {@code catalogPrice} is the price when held, to spot changes on resume. */
    public record StoredLine(Long offeringId, String name, int quantity, BigDecimal hours, Long staffId, BigDecimal unitPrice,
                             String priceReason, DiscountType discountType, BigDecimal discountValue, BigDecimal catalogPrice) {
    }

    public record Cart(List<StoredLine> lines, DiscountType discountType, BigDecimal discountValue) {
    }

    /**
     * @param lockedByMe the hold is in use by the person asking
     * @param expiresAt  when it expires if nobody resumes it (open holds only)
     */
    public record HoldDto(Long id, String holdNumber, HoldStatus status, String label, Long customerId, String customerCode, String customerName,
                          String customerPhone, String note, int itemCount, BigDecimal estimatedTotal, String createdBy, boolean createdByMe,
                          Instant createdAt, String updatedBy, Instant updatedAt, String lockedBy, boolean lockedByMe,
                          Instant lockedAt, Instant expiresAt, Long orderId, String voidReason, Instant closedAt) {
    }

    /** A resumed hold: the cart to load and what changed since it was held. */
    public record ResumeDto(HoldDto hold, Cart cart, List<String> warnings) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final EnumSet<HoldStatus> OPEN = EnumSet.of(HoldStatus.HELD, HoldStatus.IN_USE);

    private final HeldSaleRepository holds;
    private final OfferingRepository offerings;
    private final CustomerService customers;
    private final BranchContext branches;
    private final BranchRepository branchRepo;
    private final UserRepository users;
    private final TaxService taxes;
    private final AuditService audit;
    private final AppProperties.Holds props;

    public HeldSaleService(HeldSaleRepository holds, OfferingRepository offerings, CustomerService customers, BranchContext branches,
                           BranchRepository branchRepo, UserRepository users, TaxService taxes, AuditService audit, AppProperties props) {
        this.holds = holds;
        this.offerings = offerings;
        this.customers = customers;
        this.branches = branches;
        this.branchRepo = branchRepo;
        this.users = users;
        this.taxes = taxes;
        this.audit = audit;
        this.props = props.holds();
    }

    @Transactional(readOnly = true)
    public List<HoldDto> list(AuthUser user, boolean closed) {
        Long branchId = branches.currentBranchId(user);
        var statuses = closed ? EnumSet.of(HoldStatus.COMPLETED, HoldStatus.VOIDED, HoldStatus.EXPIRED) : OPEN;
        return holds.findByBranchIdAndStatusInOrderByUpdatedAtDesc(branchId, statuses).stream()
                .limit(closed ? 100 : 500).map(h -> toDto(h, user)).toList();
    }

    @Transactional(readOnly = true)
    public long openCount(AuthUser user) {
        return holds.countByBranchIdAndStatusIn(branches.currentBranchId(user), OPEN);
    }

    /** Parks a cart. */
    @Transactional
    public HoldDto hold(HoldRequest req, AuthUser user) {
        HeldSale h = new HeldSale();
        h.setBranch(branchRepo.getReferenceById(branches.currentBranchId(user)));
        h.setCreatedBy(users.getReferenceById(user.id()));
        fill(h, req, user);
        h.setStatus(HoldStatus.HELD);
        holds.saveAndFlush(h);
        h.setHoldNumber(String.format("HOLD-%05d", h.getId()));
        audit.log(AuditAction.SALE_HELD, "HeldSale", h.getId(), "Held " + h.getHoldNumber() + describe(h));
        return toDto(h, user);
    }

    /** Puts a resumed hold back with its current contents (for example after adding items). */
    @Transactional
    public HoldDto update(Long id, HoldRequest req, AuthUser user) {
        HeldSale h = lock(id, user);
        requireMine(h, user);
        fill(h, req, user);
        release(h);
        audit.log(AuditAction.SALE_HELD, "HeldSale", h.getId(), "Held " + h.getHoldNumber() + " again" + describe(h));
        return toDto(h, user);
    }

    /**
     * Takes a hold to finish it at this till. A hold in use elsewhere can only be taken over by
     * someone with "Manage held sales", or once its lock has timed out.
     */
    @Transactional
    public ResumeDto resume(Long id, boolean takeOver, AuthUser user) {
        HeldSale h = lock(id, user);
        boolean manager = user.has(Permission.HOLDS_MANAGE);
        if (h.getStatus() == HoldStatus.EXPIRED && !manager) {
            throw new ApiException(HttpStatus.CONFLICT, h.getHoldNumber() + " has expired; a manager can restore it");
        }
        if (h.getStatus() == HoldStatus.COMPLETED || h.getStatus() == HoldStatus.VOIDED) {
            throw ApiException.conflict(h.getHoldNumber() + " is already " + h.getStatus().name().toLowerCase());
        }
        if (h.getStatus() == HoldStatus.IN_USE && !lockedBy(h, user) && lockFresh(h)) {
            if (h.getOrder() != null) {
                throw ApiException.conflict(h.getHoldNumber() + " is being paid by KHQR at another till");
            }
            if (!takeOver || !manager) {
                throw new ApiException(HttpStatus.CONFLICT, h.getLockedBy().getFullName() + " is completing " + h.getHoldNumber()
                        + " at another till" + (manager ? "; take it over to continue here" : ""), "HOLD_IN_USE");
            }
        }
        String previous = h.getStatus() == HoldStatus.IN_USE && !lockedBy(h, user) ? h.getLockedBy().getFullName() : null;
        boolean restored = h.getStatus() == HoldStatus.EXPIRED;
        h.setStatus(HoldStatus.IN_USE);
        h.setLockedBy(users.getReferenceById(user.id()));
        h.setLockedAt(Instant.now());
        h.setUpdatedAt(Instant.now());
        Cart cart = read(h);
        List<String> warnings = changesSince(cart, h);
        audit.log(AuditAction.HELD_SALE_RESUMED, "HeldSale", h.getId(), "Resumed " + h.getHoldNumber()
                + (previous != null ? " (taken over from " + previous + ")" : "") + (restored ? " (restored after expiring)" : "")
                + (warnings.isEmpty() ? "" : "; changed since held: " + String.join("; ", warnings)));
        return new ResumeDto(toDto(h, user), cart, warnings);
    }

    /** Puts a resumed hold back unchanged (the cashier cleared it from their till). */
    @Transactional
    public HoldDto putBack(Long id, AuthUser user) {
        HeldSale h = lock(id, user);
        if (h.getStatus() == HoldStatus.IN_USE && lockedBy(h, user) && h.getOrder() == null) {
            release(h);
        }
        return toDto(h, user);
    }

    @Transactional
    public HoldDto voidHold(Long id, String reason, AuthUser user) {
        HeldSale h = lock(id, user);
        if (!h.getStatus().open() && h.getStatus() != HoldStatus.EXPIRED) {
            throw ApiException.conflict(h.getHoldNumber() + " is already " + h.getStatus().name().toLowerCase());
        }
        boolean manager = user.has(Permission.HOLDS_MANAGE);
        if (!manager && !h.getCreatedBy().getId().equals(user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the cashier who held it, or a manager, can void " + h.getHoldNumber());
        }
        if (h.getStatus() == HoldStatus.IN_USE && h.getOrder() != null) {
            throw ApiException.conflict(h.getHoldNumber() + " is waiting for a KHQR payment; cancel that order first");
        }
        if (h.getStatus() == HoldStatus.IN_USE && !lockedBy(h, user) && lockFresh(h) && !manager) {
            throw ApiException.conflict(h.getLockedBy().getFullName() + " is completing " + h.getHoldNumber());
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 3) {
            throw ApiException.badRequest("Say why the held sale is voided");
        }
        h.setStatus(HoldStatus.VOIDED);
        h.setVoidReason(why);
        close(h, user.id());
        audit.log(AuditAction.HELD_SALE_VOIDED, "HeldSale", h.getId(), "Voided " + h.getHoldNumber() + describe(h) + ": " + why);
        return toDto(h, user);
    }

    // ------------------------------------------------------------------ checkout integration

    /**
     * Called by checkout in the same transaction: the hold must be open, in this branch, and not
     * being finished by someone else. A paid order completes it; an unpaid KHQR order keeps it
     * locked until the payment arrives (or is cancelled).
     */
    @Transactional
    public void attachToOrder(Long id, Order order, AuthUser user) {
        HeldSale h = lock(id, user);
        if (!h.getStatus().open()) {
            throw ApiException.conflict(h.getHoldNumber() + " is already " + h.getStatus().name().toLowerCase());
        }
        if (!h.getBranch().getId().equals(order.getBranch().getId())) {
            throw ApiException.badRequest(h.getHoldNumber() + " belongs to another branch");
        }
        if (h.getStatus() == HoldStatus.IN_USE && !lockedBy(h, user) && lockFresh(h)) {
            throw ApiException.conflict(h.getLockedBy().getFullName() + " is completing " + h.getHoldNumber());
        }
        h.setOrder(order);
        h.setLockedBy(users.getReferenceById(user.id()));
        h.setLockedAt(Instant.now());
        if (order.getStatus() == com.pos.sales.OrderStatus.COMPLETED) {
            h.setStatus(HoldStatus.COMPLETED);
            close(h, user.id());
        } else {
            h.setStatus(HoldStatus.IN_USE);
        }
    }

    /** A KHQR order from a hold was paid. */
    @Transactional
    public void orderPaid(Order order) {
        holds.findByOrderId(order.getId()).filter(h -> h.getStatus() == HoldStatus.IN_USE).ifPresent(h -> {
            h.setStatus(HoldStatus.COMPLETED);
            close(h, h.getLockedBy() == null ? null : h.getLockedBy().getId());
        });
    }

    /** A KHQR order from a hold was cancelled or expired: the hold stays with the cashier, unpaid. */
    @Transactional
    public void orderCancelled(Order order) {
        holds.findByOrderId(order.getId()).filter(h -> h.getStatus() == HoldStatus.IN_USE).ifPresent(h -> {
            h.setOrder(null);
            h.setLockedAt(Instant.now());
        });
    }

    // --------------------------------------------------------------------------- maintenance

    /** Expires old holds and releases abandoned locks. Returns how many changed. */
    @Transactional
    public int housekeeping() {
        Instant now = Instant.now();
        int changed = 0;
        for (HeldSale h : holds.findAbandonedLocks(now.minus(props.lockTimeout()))) {
            release(h);
            changed++;
        }
        for (HeldSale h : holds.findByStatusAndUpdatedAtBefore(HoldStatus.HELD, now.minus(props.expiry()))) {
            h.setStatus(HoldStatus.EXPIRED);
            h.setClosedAt(now);
            audit.logAs(new AuditService.Actor(null, "system", h.getBranch().getId()), AuditAction.HELD_SALE_EXPIRED, "HeldSale",
                    h.getId(), h.getHoldNumber() + " expired after " + props.expiry().toHours() + " h without being resumed" + describe(h));
            changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------------------- helpers

    private void fill(HeldSale h, HoldRequest req, AuthUser user) {
        Map<Long, Offering> byId = offerings.findAllById(req.lines().stream().map(HoldLine::offeringId).toList()).stream()
                .collect(Collectors.toMap(Offering::getId, Function.identity()));
        var tax = taxes.current();
        List<StoredLine> lines = new ArrayList<>();
        List<PricingCalculator.Line> priced = new ArrayList<>();
        int items = 0;
        for (HoldLine l : req.lines()) {
            Offering o = byId.get(l.offeringId());
            if (o == null) {
                throw ApiException.badRequest("Something in the cart no longer exists (id " + l.offeringId() + ")");
            }
            lines.add(new StoredLine(o.getId(), o.getName(), l.quantity(), l.hours(), l.staffId(), l.unitPrice(), l.priceReason(),
                    l.discountType(), l.discountValue(), o.getPrice()));
            priced.add(new PricingCalculator.Line(l.unitPrice() != null ? l.unitPrice() : o.getPrice(), l.quantity(), l.hours(),
                    TaxService.keyFor(o, tax), l.discountType(), l.discountValue()));
            items += l.quantity();
        }
        Customer customer = req.customerId() == null ? null : customers.forSale(req.customerId());
        h.setCustomer(customer == null || customer.isDefaultCustomer() ? null : customer);
        h.setLabel(blank(req.label()));
        h.setNote(blank(req.note()));
        h.setItemCount(items);
        h.setEstimatedTotal(PricingCalculator.calculate(priced, req.discountType(), req.discountValue()).total());
        h.setPayload(JSON.writeValueAsString(new Cart(lines, req.discountType(), req.discountValue())));
        h.setUpdatedBy(users.getReferenceById(user.id()));
        h.setUpdatedAt(Instant.now());
    }

    /** What changed since the hold: prices, entries no longer for sale, the customer switched off. */
    private List<String> changesSince(Cart cart, HeldSale h) {
        List<String> out = new ArrayList<>();
        Map<Long, Offering> byId = offerings.findAllById(cart.lines().stream().map(StoredLine::offeringId).toList()).stream()
                .collect(Collectors.toMap(Offering::getId, Function.identity()));
        for (StoredLine l : cart.lines()) {
            Offering o = byId.get(l.offeringId());
            if (o == null || !o.isAvailableForSale()) {
                out.add(l.name() + " is no longer for sale");
            } else if (l.catalogPrice() != null && o.getPrice().compareTo(l.catalogPrice()) != 0) {
                out.add(o.getName() + " price changed from " + l.catalogPrice() + " to " + o.getPrice()
                        + (l.unitPrice() != null ? " (the changed price on this line stays)" : ""));
            }
        }
        if (h.getCustomer() != null && !h.getCustomer().isActive()) {
            out.add(h.getCustomer().getName() + " is no longer an active customer");
        }
        return out;
    }

    private Cart read(HeldSale h) {
        return JSON.readValue(h.getPayload(), Cart.class);
    }

    private HeldSale lock(Long id, AuthUser user) {
        HeldSale h = holds.lockById(id).orElseThrow(() -> ApiException.notFound("Held sale"));
        if (!h.getBranch().getId().equals(branches.currentBranchId(user))) {
            throw ApiException.notFound("Held sale");
        }
        return h;
    }

    private void requireMine(HeldSale h, AuthUser user) {
        if (h.getStatus() != HoldStatus.IN_USE || !lockedBy(h, user)) {
            throw ApiException.conflict("Resume " + h.getHoldNumber() + " before changing it");
        }
        if (h.getOrder() != null) {
            throw ApiException.conflict(h.getHoldNumber() + " is waiting for a KHQR payment");
        }
    }

    private static boolean lockedBy(HeldSale h, AuthUser user) {
        return h.getLockedBy() != null && h.getLockedBy().getId().equals(user.id());
    }

    private boolean lockFresh(HeldSale h) {
        return h.getLockedAt() != null && h.getLockedAt().isAfter(Instant.now().minus(props.lockTimeout()));
    }

    private static void release(HeldSale h) {
        h.setStatus(HoldStatus.HELD);
        h.setLockedBy(null);
        h.setLockedAt(null);
        h.setUpdatedAt(Instant.now());
    }

    private void close(HeldSale h, Long userId) {
        h.setClosedAt(Instant.now());
        h.setClosedBy(userId == null ? null : users.getReferenceById(userId));
        h.setLockedBy(null);
        h.setLockedAt(null);
        h.setUpdatedAt(Instant.now());
    }

    private static String describe(HeldSale h) {
        String who = h.getCustomer() != null ? h.getCustomer().getName() : h.getLabel();
        return " (" + h.getItemCount() + " item" + (h.getItemCount() == 1 ? "" : "s") + ", ~" + h.getEstimatedTotal()
                + (who == null ? "" : ", " + who) + ")";
    }

    private HoldDto toDto(HeldSale h, AuthUser user) {
        Customer c = h.getCustomer();
        Instant expires = h.getStatus() == HoldStatus.HELD ? h.getUpdatedAt().plus(props.expiry()) : null;
        return new HoldDto(h.getId(), h.getHoldNumber(), h.getStatus(), h.getLabel(), c == null ? null : c.getId(),
                c == null ? null : c.getCode(), c == null ? null : c.getName(), c == null ? null : c.getPhone(), h.getNote(), h.getItemCount(), h.getEstimatedTotal(),
                h.getCreatedBy().getFullName(), h.getCreatedBy().getId().equals(user.id()), h.getCreatedAt(), h.getUpdatedBy() == null ? null : h.getUpdatedBy().getFullName(),
                h.getUpdatedAt(), h.getLockedBy() == null ? null : h.getLockedBy().getFullName(), lockedBy(h, user), h.getLockedAt(),
                expires, h.getOrder() == null ? null : h.getOrder().getId(), h.getVoidReason(), h.getClosedAt());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
