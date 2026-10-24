package com.pos.sales;

import org.springframework.http.HttpStatus;
import com.pos.access.Permission;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import com.pos.sales.SalesDtos.ReturnRequest;
import com.pos.tax.TaxSettings;
import com.pos.tax.TaxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.auth.User;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.OfferingType;
import com.pos.catalog.PackageExpander;
import com.pos.catalog.ServiceComponent;
import com.pos.common.ApiException;
import com.pos.settings.SettingsService;
import com.pos.currency.CashSettlement;
import com.pos.currency.CashSettlement.Tender;
import com.pos.currency.CurrencyService;
import com.pos.currency.Money;
import com.pos.inventory.BranchContext;
import com.pos.inventory.MovementType;
import com.pos.inventory.StockService;
import com.pos.inventory.StockService.Ref;
import com.pos.inventory.Storage;
import com.pos.payment.KhqrService;
import com.pos.payment.Payment;
import com.pos.payment.PaymentDto;
import com.pos.payment.PaymentMethod;
import com.pos.payment.PaymentRepository;
import com.pos.payment.PaymentStatus;
import com.pos.sales.PricingCalculator.Line;
import com.pos.sales.SalesDtos.CartLine;
import com.pos.sales.SalesDtos.CheckoutRequest;
import com.pos.sales.SalesDtos.OrderDto;
import com.pos.sales.SalesDtos.OrderSummaryDto;

/**
 * Checkout and the order lifecycle. Stock is taken when the order is created (a KHQR order
 * reserves it while waiting for payment) and returned when the order is cancelled, expires or is
 * refunded. Stock moves for products and items sold directly and for the parts services use.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final OfferingRepository offerings;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final KhqrService khqr;
    private final SettingsService settings;
    private final TransactionTemplate tx;

    private final StockService stock;
    private final BranchContext branches;
    private final CurrencyService currencies;
    private final AuditService audit;
    private final TaxService taxes;
    private final com.pos.customer.CustomerService customers;
    private final OrderReturnRepository returnsRepo;
    private final com.pos.hold.HeldSaleService heldSales;

    public OrderService(OrderRepository orders, OfferingRepository offerings, PaymentRepository payments,
                        UserRepository users, KhqrService khqr, SettingsService settings, TransactionTemplate tx,
                        StockService stock, BranchContext branches, CurrencyService currencies, AuditService audit,
                        TaxService taxes, com.pos.customer.CustomerService customers, OrderReturnRepository returnsRepo,
                        com.pos.hold.HeldSaleService heldSales) {
        this.heldSales = heldSales;
        this.returnsRepo = returnsRepo;
        this.customers = customers;
        this.taxes = taxes;
        this.currencies = currencies;
        this.audit = audit;
        this.orders = orders;
        this.offerings = offerings;
        this.payments = payments;
        this.users = users;
        this.khqr = khqr;
        this.settings = settings;
        this.tx = tx;
        this.stock = stock;
        this.branches = branches;
    }

    @Transactional
    public OrderDto checkout(CheckoutRequest req, AuthUser cashier) {
        // One read of the tax settings per sale, so every line is taxed under the same rules.
        TaxSettings tax = taxes.current();
        Set<Long> ids = req.items().stream().map(CartLine::offeringId).collect(Collectors.toSet());
        Map<Long, Offering> byId = offerings.findWithComponentsByIdIn(ids).stream()
                .collect(Collectors.toMap(Offering::getId, Function.identity()));
        User cashierUser = users.getReferenceById(cashier.id());
        Storage selling = branches.sellingStorage(cashier);

        Order order = new Order();
        order.setCashier(cashierUser);
        order.setBranch(selling.getBranch());
        order.setStorage(selling);
        order.setCurrency(currencies.base().code());
        order.setNote(blankToNull(req.note()));
        // The customer picked, or the Walk-in Customer; the name is kept as it was at the sale.
        var customer = customers.forSale(req.customerId());
        order.setCustomer(customer);
        order.setCustomerName(customer.isDefaultCustomer() ? blankToNull(req.customerName()) : customer.getName());
        order.setDiscountType(req.discountType());
        order.setDiscountValue(req.discountType() == null ? null : req.discountValue());
        order.setTaxMode(tax.mode());
        order.setTaxLabel(tax.label());

        for (CartLine line : req.items()) {
            order.addItem(toOrderItem(line, byId.get(line.offeringId()), cashier, tax));
        }

        requireItemDiscountsAllowed(order, cashier);
        var totals = PricingCalculator.calculate(
                order.getItems().stream().map(OrderService::pricingLine).toList(),
                req.discountType(), req.discountValue());
        order.setSubtotal(totals.subtotal());
        order.setDiscountAmount(totals.discount());
        order.setItemDiscount(totals.itemDiscount());
        order.setTaxAmount(totals.tax());
        order.setTaxIncluded(totals.taxIncluded());
        order.setTotal(totals.total());
        order.setTaxableAmount(totals.taxableAmount());
        order.setNonTaxableAmount(totals.nonTaxableAmount());
        if (req.expectedTotal() != null && req.expectedTotal().compareTo(totals.total()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Prices changed: the total is now " + totals.total() + " " + order.getCurrency()
                    + ", not " + req.expectedTotal() + ". Check the cart and charge again.", "PRICES_CHANGED");
        }
        totals.taxes().forEach(order::addTax);
        for (int i = 0; i < order.getItems().size(); i++) {
            order.getItems().get(i).apply(totals.lines().get(i));
        }

        Map<String, Money> rates = currencies.active();
        Money base = currencies.base();
        rates.values().forEach(m -> order.addRate(m.code(), m.rate()));

        if (req.paymentMethod() == PaymentMethod.CASH) {
            List<Tender> tenders = req.tenders() == null || req.tenders().isEmpty()
                    ? List.of(new Tender(base, totals.total()))
                    : req.tenders().stream().map(t -> new Tender(currencies.require(rates, t.currency()), t.amount())).toList();
            Money changeIn = req.changeCurrency() == null ? base : currencies.require(rates, req.changeCurrency());
            var settlement = CashSettlement.settle(totals.total(), tenders, changeIn);
            order.complete();
            order.addPayment(Payment.cash(totals.total(), base, settlement));
            orders.saveAndFlush(order);
            order.setOrderNumber(orderNumber(order));
        } else {
            if (totals.total().signum() <= 0) {
                throw ApiException.badRequest("KHQR needs a total above zero");
            }
            Money chargeIn = req.khqrCurrency() == null ? base : currencies.require(rates, req.khqrCurrency());
            order.setStatus(OrderStatus.PENDING_PAYMENT);
            orders.saveAndFlush(order);
            order.setOrderNumber(orderNumber(order));
            order.addPayment(khqr.createPayment(totals.total(), chargeIn, order.getOrderNumber()));
        }
        orders.flush();
        if (req.heldSaleId() != null) {
            heldSales.attachToOrder(req.heldSaleId(), order, cashier);
        }
        logOverrides(order);
        logItemDiscounts(order);
        // After saving, so the history can point at the order. Failing here rolls the order back.
        Ref ref = new Ref(cashier.id(), order.getId(), null, null);
        StockMovements.of(order.getItems())
                .forEach((offeringId, qty) -> stock.take(selling.getId(), offeringId, qty, MovementType.SALE, ref));
        return OrderDto.from(order);
    }

    /** {@code branchId} null means every branch. */
    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> search(Long branchId, OrderStatus status, Instant from, Instant to, String q, Long customerId,
                                        Pageable pageable) {
        return orders.search(branchId, status, from, to, blankToNull(q), customerId, pageable).map(OrderSummaryDto::from);
    }

    @Transactional(readOnly = true)
    public Page<SalesDtos.ReturnSummaryDto> returns(Long branchId, Instant from, Instant to, Long customerId, String q, Pageable pageable) {
        return returnsRepo.search(branchId, from, to, customerId, blankToNull(q), pageable).map(SalesDtos.ReturnSummaryDto::from);
    }

    /** Staff can open orders of their own branch; admins any. */
    @Transactional(readOnly = true)
    public OrderDto get(Long id, AuthUser user) {
        Order order = orders.findWithItemsById(id).orElseThrow(() -> ApiException.notFound("Order"));
        branches.requireBranch(user, order.getBranch().getId());
        return OrderDto.from(order);
    }

    /** Cancels an order that is still waiting for payment. */
    @Transactional
    public OrderDto cancel(Long id, AuthUser user) {
        Order order = lock(id);
        branches.requireBranch(user, order.getBranch().getId());
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("Only orders waiting for payment can be cancelled");
        }
        closeUnpaid(order, PaymentStatus.CANCELLED, user.id());
        audit.log(AuditAction.ORDER_CANCELLED, "Order", order.getId(), "Cancelled unpaid order " + order.getOrderNumber()
                + " (" + order.getTotal() + " " + order.getCurrency() + ")");
        return OrderDto.from(order);
    }

    /** Refunds everything not yet returned and puts it back in the storage it came from. */
    @Transactional
    public OrderDto refund(Long id, AuthUser user) {
        Order order = lock(id);
        branches.requireBranch(user, order.getBranch().getId());
        requireReturnable(order);
        Map<OrderItem, Integer> all = new LinkedHashMap<>();
        order.getItems().stream().filter(i -> i.returnableQty() > 0).forEach(i -> all.put(i, i.returnableQty()));
        boolean wasPartial = order.getStatus() == OrderStatus.PARTIALLY_REFUNDED;
        OrderReturn ret = applyReturn(order, all, wasPartial ? "Refund of the rest" : "Full refund", user);
        audit.log(AuditAction.ORDER_REFUNDED, "Order", order.getId(), "Refunded order " + order.getOrderNumber()
                + (wasPartial ? " (the rest: " : " (") + ret.getTotal() + " " + order.getCurrency() + ", "
                + order.getTaxLabel() + " " + ret.getTax() + ") as " + ret.getReturnNumber());
        return OrderDto.from(order);
    }

    /**
     * Takes back some units of some lines. Each unit refunds its share of what the line was paid,
     * tax included, so the tax refunded matches the tax charged; the last units of a line refund
     * exactly what is left of it, so a line (and the order) is never refunded more than was paid.
     */
    @Transactional
    public OrderDto returnItems(Long id, ReturnRequest req, AuthUser user) {
        Order order = lock(id);
        branches.requireBranch(user, order.getBranch().getId());
        requireReturnable(order);
        Map<Long, OrderItem> byId = order.getItems().stream().collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<OrderItem, Integer> picked = new LinkedHashMap<>();
        for (var line : req.lines()) {
            OrderItem item = byId.get(line.orderItemId());
            if (item == null) {
                throw ApiException.badRequest("That line is not part of order " + order.getOrderNumber());
            }
            picked.merge(item, line.quantity(), Integer::sum);
        }
        picked.forEach((item, qty) -> {
            if (qty > item.returnableQty()) {
                throw ApiException.badRequest("Only " + item.returnableQty() + " of " + item.getName() + " can still be returned");
            }
        });
        OrderReturn ret = applyReturn(order, picked, blankToNull(req.reason()), user);
        audit.log(AuditAction.ORDER_RETURNED, "Order", order.getId(), "Return " + ret.getReturnNumber() + " on order "
                + order.getOrderNumber() + ": " + ret.getLines().stream().map(l -> l.getQuantity() + " x " + l.getOrderItem().getName())
                .collect(Collectors.joining(", ")) + " (" + ret.getTotal() + " " + order.getCurrency() + ", "
                + order.getTaxLabel() + " " + ret.getTax() + ")");
        return OrderDto.from(order);
    }

    private static void requireReturnable(Order order) {
        if (order.getStatus() != OrderStatus.COMPLETED && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
            throw ApiException.conflict("Only completed orders can be refunded");
        }
    }

    private OrderReturn applyReturn(Order order, Map<OrderItem, Integer> picked, String reason, AuthUser user) {
        OrderReturn ret = new OrderReturn();
        ret.setReason(reason);
        ret.setCreatedBy(users.getReferenceById(user.id()));
        Map<Long, Integer> back = new TreeMap<>();
        picked.forEach((item, qty) -> {
            RefundSplit.Share share = RefundSplit.share(item, qty);
            ret.addLine(item, qty, share.amount(), share.tax());
            StockMovements.forReturn(item, qty).forEach((offeringId, n) -> back.merge(offeringId, n, Integer::sum));
            item.setRefundedQty(item.getRefundedQty() + qty);
            item.setRefundedAmount(item.getRefundedAmount().add(share.amount()));
            item.setRefundedTax(item.getRefundedTax().add(share.tax()));
        });
        order.addReturn(ret);
        order.setRefundedTotal(order.getRefundedTotal().add(ret.getTotal()));
        order.setRefundedTax(order.getRefundedTax().add(ret.getTax()));
        boolean all = order.getItems().stream().allMatch(i -> i.returnableQty() == 0);
        order.setStatus(all ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
        if (all) {
            order.getPayments().stream().filter(p -> p.getStatus() == PaymentStatus.PAID)
                    .forEach(p -> p.setStatus(PaymentStatus.REFUNDED));
        }
        orders.flush();
        ret.setReturnNumber(String.format("RET-%05d", ret.getId()));
        Ref ref = new Ref(user.id(), order.getId(), null, "Return " + ret.getReturnNumber());
        back.forEach((offeringId, qty) -> stock.add(order.getStorage().getId(), offeringId, qty, MovementType.REFUND, ref));
        return ret;
    }

    /**
     * Returns the payment's current state. A pending KHQR payment is first checked with Bakong, and
     * expired if its time is up. The Bakong call runs outside any transaction.
     */
    public PaymentDto refreshPayment(Long paymentId) {
        Payment snapshot = tx.execute(s -> payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment")));
        if (snapshot.getStatus() != PaymentStatus.PENDING) {
            return tx.execute(s -> PaymentDto.from(payments.findById(paymentId).orElseThrow()));
        }
        Optional<String> settlement = khqr.findSettlement(snapshot);
        return tx.execute(s -> PaymentDto.from(settle(snapshot.getOrder().getId(), paymentId, settlement)));
    }

    /** Marks a pending KHQR payment as paid without asking Bakong. Only allowed in simulated mode. */
    public PaymentDto simulatePayment(Long paymentId) {
        if (!khqr.isSimulated()) {
            throw ApiException.badRequest("Simulated payments are turned off");
        }
        Long orderId = tx.execute(s -> payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment")).getOrder().getId());
        return tx.execute(s -> PaymentDto.from(settle(orderId, paymentId, Optional.of("SIMULATED-" + paymentId))));
    }

    /** Expires pending KHQR payments whose time is up, unless Bakong says they were paid. */
    public void expireOverduePayments() {
        for (Payment p : payments.findByStatusAndExpiresAtBefore(PaymentStatus.PENDING, Instant.now())) {
            try {
                refreshPayment(p.getId());
            } catch (RuntimeException e) {
                log.warn("Could not expire payment {}", p.getId(), e);
            }
        }
    }

    private Payment settle(Long orderId, Long paymentId, Optional<String> settlement) {
        Order order = lock(orderId);
        Payment payment = order.getPayments().stream().filter(p -> p.getId().equals(paymentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return payment;
        }
        if (settlement.isPresent()) {
            payment.markPaid(settlement.get());
            order.complete();
            heldSales.orderPaid(order);
            log.info("Order {} paid by KHQR ({})", order.getOrderNumber(), settlement.get());
        } else if (payment.isExpired(Instant.now())) {
            closeUnpaid(order, PaymentStatus.EXPIRED, null);
        }
        return payment;
    }

    /** {@code userId} is null when the system expires an order. */
    private void closeUnpaid(Order order, PaymentStatus paymentStatus, Long userId) {
        order.setStatus(OrderStatus.CANCELLED);
        order.getPayments().stream().filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .forEach(p -> p.setStatus(paymentStatus));
        returnStock(order, MovementType.SALE_RETURN, userId);
        heldSales.orderCancelled(order);
    }

    private OrderItem toOrderItem(CartLine line, Offering offering, AuthUser cashier, TaxSettings tax) {
        if (offering == null || !offering.isAvailableForSale()) {
            throw ApiException.badRequest("Something in the cart is no longer for sale (id " + line.offeringId() + ")");
        }
        boolean service = offering.getType() == OfferingType.SERVICE;
        boolean pkg = offering.getType() == OfferingType.PACKAGE;
        PackageExpander.Expansion expansion = pkg ? PackageExpander.expand(offering, line.quantity()) : null;
        if (offering.isHourly() && line.hours() == null) {
            throw ApiException.badRequest("Enter the hours worked for " + offering.getName());
        }
        if (!offering.isHourly() && line.hours() != null) {
            throw ApiException.badRequest(offering.getName() + " is not charged by the hour");
        }
        boolean hasServiceWork = service || (pkg && expansion.containsServices());
        if (!hasServiceWork && line.staffId() != null) {
            throw ApiException.badRequest("Staff can only be assigned to services, or packages that include one");
        }

        OrderItem item = new OrderItem();
        item.setOffering(offering);
        item.setOfferingType(offering.getType());
        item.setName(offering.getName());
        item.setNameKm(offering.getNameKm());
        item.setSku(offering.getSku());
        item.setUnit(offering.getUnitSymbol());
        item.setListPrice(offering.getPrice());
        item.setUnitPrice(priceFor(line, offering, cashier, item));
        if (line.discountType() != null && line.discountValue() != null && line.discountValue().signum() > 0) {
            item.setItemDiscountType(line.discountType());
            item.setItemDiscountValue(line.discountValue());
        }
        item.setQuantity(line.quantity());
        item.setHours(line.hours());
        // The rate actually charged: 0 under No VAT, for types outside the tax scope, or exempt rates.
        PricingCalculator.TaxKey key = TaxService.keyFor(offering, tax);
        item.setTaxRateId(key.rateId());
        item.setTaxName(key.name());
        item.setTaxRate(key.rate());
        item.setPriceIncludesTax(key.included());
        item.setLineTotal(pricingLine(item).total());

        if (hasServiceWork) {
            item.setDurationMinutes(offering.getDurationMinutes());
            User staff = line.staffId() == null || line.staffId().equals(cashier.id())
                    ? users.findById(cashier.id()).orElseThrow()
                    : users.findById(line.staffId()).filter(User::isActive)
                            .orElseThrow(() -> ApiException.badRequest("The staff member assigned to "
                                    + offering.getName() + " was not found or is disabled"));
            item.setStaff(staff);
            item.setStaffName(staff.getFullName());
        }
        if (service) {
            for (ServiceComponent part : offering.getComponents()) {
                item.addComponent(part.getItem(), part.getQuantity() * line.quantity());
            }
        }
        if (pkg) {
            expansion.stock().forEach((id, qty) -> item.addComponent(expansion.stocked().get(id), qty));
            for (var c : expansion.contents()) {
                item.addContent(c.offering(), c.quantity(), c.unit(), c.depth());
            }
        }
        item.setCostTotal(costOf(item, offering, pkg ? expansion : null));
        return item;
    }

    /**
     * The price a line sells at: the catalog price, unless the cashier changed it. Changing it needs
     * "Override prices" and a reason; going below the item's cost also needs "Sell below cost". The
     * new price is the listed price under the store's tax mode (VAT is then included or added as
     * usual) and order discounts apply on top of it.
     */
    private BigDecimal priceFor(CartLine line, Offering offering, AuthUser cashier, OrderItem item) {
        BigDecimal catalog = offering.getPrice();
        if (line.unitPrice() == null || line.unitPrice().compareTo(catalog) == 0) {
            return catalog;
        }
        BigDecimal price = com.pos.common.Money.round(line.unitPrice());
        if (!cashier.has(Permission.PRICE_OVERRIDE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can't change prices. " + offering.getName()
                    + " sells at " + catalog + "; ask someone allowed to override prices.");
        }
        String reason = blankToNull(line.priceReason());
        if (reason == null || reason.length() < 3) {
            throw ApiException.badRequest("Give a reason for changing the price of " + offering.getName());
        }
        BigDecimal cost = offering.getCost() == null ? BigDecimal.ZERO : offering.getCost();
        if (price.compareTo(cost) < 0 && !cashier.has(Permission.PRICE_BELOW_COST)) {
            throw new ApiException(HttpStatus.FORBIDDEN, offering.getName() + " can't be sold below its cost (" + cost
                    + ") without the \"Sell below cost\" permission");
        }
        item.setPriceOverridden(true);
        item.setPriceOverrideReason(reason);
        item.setPriceOverrideBy(users.getReferenceById(cashier.id()));
        return price;
    }

    /**
     * What a line costs at today's catalog costs: goods (cost x quantity), a service's labour
     * (cost x jobs, x hours when hourly) plus its parts, or for a package everything stocked inside
     * plus the labour of services inside.
     */
    private static BigDecimal costOf(OrderItem item, Offering offering, PackageExpander.Expansion expansion) {
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal own = offering.getCost() == null ? BigDecimal.ZERO : offering.getCost();
        switch (offering.getType()) {
            case PRODUCT, ITEM -> cost = own.multiply(BigDecimal.valueOf(item.getQuantity()));
            case SERVICE -> cost = own.multiply(BigDecimal.valueOf(item.getQuantity()))
                    .multiply(item.getHours() == null ? BigDecimal.ONE : item.getHours());
            case PACKAGE -> {
                for (var c : expansion.contents()) {
                    if (c.offering().getType() == OfferingType.SERVICE && c.offering().getCost() != null) {
                        cost = cost.add(c.offering().getCost().multiply(c.quantity()));
                    }
                }
            }
        }
        for (OrderItemComponent used : item.getComponents()) {
            BigDecimal partCost = used.getItem().getCost() == null ? BigDecimal.ZERO : used.getItem().getCost();
            cost = cost.add(partCost.multiply(BigDecimal.valueOf(used.getQuantity())));
        }
        return cost.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Item discounts need "Discount items", and each may take at most the role's maximum % off its
     * line (worked out on the line as priced, so % and amount discounts are held to the same limit).
     */
    private void requireItemDiscountsAllowed(Order order, AuthUser cashier) {
        BigDecimal max = cashier.isSuperAdmin() ? null : cashier.role().maxItemDiscountPct();
        for (OrderItem i : order.getItems()) {
            if (i.getItemDiscountType() == null) {
                continue;
            }
            Line line = pricingLine(i);
            BigDecimal off = line.itemDiscount();
            if (off.signum() == 0) {
                continue;
            }
            if (!cashier.has(Permission.DISCOUNT_ITEMS)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You can't discount single items (" + i.getName() + ")");
            }
            // A % discount is held to the % entered (cent rounding can't push it over); an amount
            // is held to the % of the line it takes off.
            BigDecimal lineTotal = line.gross();
            BigDecimal pct = i.getItemDiscountType() == DiscountType.PERCENT ? i.getItemDiscountValue()
                    : lineTotal.signum() == 0 ? BigDecimal.ZERO
                    : off.multiply(BigDecimal.valueOf(100)).divide(lineTotal, 4, java.math.RoundingMode.HALF_UP);
            if (max != null && pct.compareTo(max) > 0) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Your role can discount items by up to "
                        + max.stripTrailingZeros().toPlainString() + "% (" + i.getName() + " would be "
                        + pct.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%)");
            }
        }
    }

    /** One audit entry per sale with item discounts. */
    private void logItemDiscounts(Order order) {
        List<OrderItem> discounted = order.getItems().stream().filter(i -> i.getItemDiscount().signum() > 0).toList();
        if (discounted.isEmpty()) {
            return;
        }
        String what = discounted.stream().map(i -> i.getName() + " -" + i.getItemDiscount()
                + (i.getItemDiscountType() == DiscountType.PERCENT ? " (" + i.getItemDiscountValue().stripTrailingZeros().toPlainString() + "%)" : ""))
                .collect(Collectors.joining("; "));
        audit.log(AuditAction.ITEM_DISCOUNTED, "Order", order.getId(), "Item discounts on order " + order.getOrderNumber() + ": " + what);
    }

    /** One audit entry per sale with changed prices: each line's catalog and charged price, and why. */
    private void logOverrides(Order order) {
        List<OrderItem> changed = order.getItems().stream().filter(OrderItem::isPriceOverridden).toList();
        if (changed.isEmpty()) {
            return;
        }
        String what = changed.stream().map(i -> i.getName() + " " + i.getListPrice() + " → " + i.getUnitPrice()
                + " (" + i.getPriceOverrideReason() + ")").collect(Collectors.joining("; "));
        audit.log(AuditAction.PRICE_OVERRIDDEN, "Order", order.getId(), "Changed prices on order " + order.getOrderNumber()
                + ": " + what);
    }

    private static Line pricingLine(OrderItem i) {
        return new Line(i.getUnitPrice(), i.getQuantity(), i.getHours(),
                new PricingCalculator.TaxKey(i.getTaxRateId(), i.getTaxName(), i.getTaxRate(), i.isPriceIncludesTax()),
                i.getItemDiscountType(), i.getItemDiscountValue());
    }

    private void returnStock(Order order, MovementType type, Long userId) {
        Ref ref = new Ref(userId, order.getId(), null, null);
        StockMovements.of(order.getItems())
                .forEach((offeringId, qty) -> stock.add(order.getStorage().getId(), offeringId, qty, type, ref));
    }

    private Order lock(Long id) {
        return orders.lockById(id).orElseThrow(() -> ApiException.notFound("Order"));
    }

    private String orderNumber(Order order) {
        LocalDate day = order.getCreatedAt().atZone(settings.current().timezone()).toLocalDate();
        return String.format("%1$tY%1$tm%1$td-%2$05d", day, order.getId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

}
