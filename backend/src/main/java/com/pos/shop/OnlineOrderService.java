package com.pos.shop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.inventory.BranchContext;
import com.pos.sales.OrderStatus;

/**
 * Online orders for staff (the till's Online orders page): what to prepare, and marking each ready
 * to collect, out for delivery and done. Paying, cancelling and refunding are the order's own
 * (Sales), as for any sale.
 */
@Service
public class OnlineOrderService {

    /** {@code paid}: the KHQR payment has arrived (only paid orders are prepared). */
    public record OnlineOrderDto(Long orderId, String orderNumber, OrderStatus orderStatus, boolean paid,
                                 OnlineOrder.Fulfilment fulfilment, OnlineOrder.Status status, String branchName,
                                 String contactName, String contactPhone, String address, String note, BigDecimal total,
                                 String currency, List<Line> items, Instant createdAt, Instant readyAt, Instant doneAt) {
    }

    public record Line(String name, String nameKm, String unit, int quantity) {
    }

    /** Which list to show: to prepare (new), ready or on the way, done, or still waiting for payment. */
    public enum View { NEW, READY, DONE, UNPAID }

    private final OnlineOrderRepository onlineOrders;
    private final ShopSettingsService settings;
    private final BranchContext branches;
    private final UserRepository users;
    private final AuditService audit;

    public OnlineOrderService(OnlineOrderRepository onlineOrders, ShopSettingsService settings, BranchContext branches,
                              UserRepository users, AuditService audit) {
        this.onlineOrders = onlineOrders;
        this.settings = settings;
        this.branches = branches;
        this.users = users;
        this.audit = audit;
    }

    /** {@code branchId}: for staff who see every branch, one branch or null for all; others always see their own. */
    @Transactional(readOnly = true)
    public List<OnlineOrderDto> list(View view, Long branchId, AuthUser user) {
        Long scope = branches.viewScope(user, branchId);
        Set<OrderStatus> orderStatuses = view == View.UNPAID ? EnumSet.of(OrderStatus.PENDING_PAYMENT)
                : view == View.DONE ? EnumSet.of(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)
                : EnumSet.of(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED);
        Set<OnlineOrder.Status> statuses = switch (view) {
            case NEW, UNPAID -> EnumSet.of(OnlineOrder.Status.NEW);
            case READY -> EnumSet.of(OnlineOrder.Status.READY, OnlineOrder.Status.OUT_FOR_DELIVERY);
            case DONE -> EnumSet.of(OnlineOrder.Status.DONE);
        };
        List<OnlineOrder> found = onlineOrders.findForStaff(orderStatuses, statuses, scope);
        if (view == View.DONE) {
            // The latest first, and only the last 100.
            found = found.reversed().stream().limit(100).toList();
        }
        Long deliveryId = deliveryOfferingId();
        return found.stream().map(oo -> toDto(oo, deliveryId)).toList();
    }

    /** Paid orders still to prepare: the badge on the Online orders page. */
    @Transactional(readOnly = true)
    public long countNew(AuthUser user) {
        return onlineOrders.countNewPaid(branches.viewScope(user, null));
    }

    /**
     * Moves a paid order on: NEW to READY (pickup) or OUT_FOR_DELIVERY (delivery), then DONE (or
     * straight to DONE when handed over at once). Under a lock, so two tills can't both move it.
     */
    @Transactional
    public OnlineOrderDto setStatus(Long orderId, OnlineOrder.Status next, AuthUser user) {
        OnlineOrder oo = onlineOrders.lockByOrderId(orderId).orElseThrow(() -> ApiException.notFound("Online order"));
        branches.requireBranch(user, oo.getOrder().getBranch().getId());
        OrderStatus paid = oo.getOrder().getStatus();
        if (paid != OrderStatus.COMPLETED && paid != OrderStatus.PARTIALLY_REFUNDED) {
            throw ApiException.conflict(paid == OrderStatus.PENDING_PAYMENT ? "This order hasn't been paid yet."
                    : "This order was cancelled or refunded.");
        }
        OnlineOrder.Status from = oo.getStatus();
        boolean pickup = oo.getFulfilment() == OnlineOrder.Fulfilment.PICKUP;
        boolean allowed = switch (next) {
            case READY -> pickup && from == OnlineOrder.Status.NEW;
            case OUT_FOR_DELIVERY -> !pickup && from == OnlineOrder.Status.NEW;
            case DONE -> from != OnlineOrder.Status.DONE;
            case NEW -> false;
        };
        if (!allowed) {
            throw ApiException.conflict("This order is already " + label(from) + ".");
        }
        Instant now = Instant.now();
        oo.setStatus(next);
        oo.setUpdatedAt(now);
        oo.setUpdatedBy(users.getReferenceById(user.id()));
        if (next == OnlineOrder.Status.READY || next == OnlineOrder.Status.OUT_FOR_DELIVERY) {
            oo.setReadyAt(now);
        }
        if (next == OnlineOrder.Status.DONE) {
            oo.setDoneAt(now);
        }
        audit.log(AuditAction.ONLINE_ORDER_UPDATED, "Order", orderId,
                "Online order " + oo.getOrder().getOrderNumber() + ": " + label(next));
        return toDto(oo, deliveryOfferingId());
    }

    private Long deliveryOfferingId() {
        var d = settings.load().getDeliveryOffering();
        return d == null ? null : d.getId();
    }

    private static OnlineOrderDto toDto(OnlineOrder oo, Long deliveryId) {
        var o = oo.getOrder();
        List<Line> items = o.getItems().stream()
                .filter(i -> deliveryId == null || i.getOffering() == null || !deliveryId.equals(i.getOffering().getId()))
                .map(i -> new Line(i.getName(), i.getNameKm(), i.getUnit(), i.getQuantity())).toList();
        boolean paid = o.getStatus() != OrderStatus.PENDING_PAYMENT && o.getStatus() != OrderStatus.CANCELLED;
        String note = o.getNote() == null ? null : o.getNote().replaceFirst("^Online (pickup|delivery)(: )?", "");
        return new OnlineOrderDto(o.getId(), o.getOrderNumber(), o.getStatus(), paid, oo.getFulfilment(), oo.getStatus(),
                o.getBranch().getName(), oo.getContactName(), oo.getContactPhone(), oo.getAddress(),
                note == null || note.isBlank() ? null : note, o.getTotal(), o.getCurrency(), items, oo.getCreatedAt(),
                oo.getReadyAt(), oo.getDoneAt());
    }

    private static String label(OnlineOrder.Status s) {
        return switch (s) {
            case NEW -> "new";
            case READY -> "ready to collect";
            case OUT_FOR_DELIVERY -> "out for delivery";
            case DONE -> "done";
        };
    }
}
