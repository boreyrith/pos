package com.pos.shop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.branch.Branch;
import com.pos.branch.BranchRepository;
import com.pos.catalog.CatalogDtos.OfferingDto;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.OfferingService;
import com.pos.catalog.PricingMode;
import com.pos.common.ApiException;
import com.pos.common.OfferingType;
import com.pos.currency.CurrencyService;
import com.pos.inventory.Storage;
import com.pos.marketing.PromotionDtos.EvaluateRequest;
import com.pos.marketing.PromotionDtos.EvaluateResponse;
import com.pos.marketing.PromotionService;
import com.pos.payment.PaymentDto;
import com.pos.payment.PaymentMethod;
import com.pos.payment.PaymentStatus;
import com.pos.sales.Order;
import com.pos.sales.OrderRepository;
import com.pos.sales.OrderService;
import com.pos.sales.OrderStatus;
import com.pos.sales.SalesDtos.CartLine;
import com.pos.sales.SalesDtos.CheckoutRequest;
import com.pos.sales.SalesDtos.OrderDto;
import com.pos.settings.SettingsService;
import com.pos.tax.TaxRate;
import com.pos.tax.TaxRateRepository;

/**
 * The online shop for customers: what is for sale at a branch, what promotions take off a cart, and
 * placing and following orders. An order is placed as an ordinary sale (OrderService.checkout) by
 * the built-in Online shop user at the branch, paid by KHQR; stock is held until it is paid or its
 * code expires. Prices, promotions, tax and the total are the server's: the client shows the total
 * it worked out ({@code expectedTotal}) and the order is refused if it no longer matches.
 */
@Service
public class ShopService {

    /** At most this many of one entry per order, and this many unpaid orders per phone at a time. */
    static final int MAX_QUANTITY = 50;
    static final int MAX_UNPAID_PER_PHONE = 3;

    public record BranchDto(Long id, String name, String address) {
    }

    public record CurrencyDto(String code, String symbol, int decimals, BigDecimal rate, boolean base) {
    }

    /** {@code fee} null or delivery off: no delivery. {@code product}: the Delivery line, for working out totals. */
    public record DeliveryDto(boolean enabled, BigDecimal fee, BigDecimal freeFrom, Long branchId, ProductDto product) {
    }

    /** What every shop page needs: the store, its tax rules (to work out totals as the till does) and the shop's options. */
    public record StoreDto(String name, boolean hasLogo, int logoVersion, String currency, List<CurrencyDto> currencies,
                           String taxMode, String taxLabel, Set<OfferingType> taxScope, Long defaultTaxRateId, String timezone,
                           boolean open, boolean pickupEnabled, List<BranchDto> pickupBranches, DeliveryDto delivery,
                           boolean khqrSimulated) {
    }

    public record CategoryDto(Long id, String name) {
    }

    /**
     * An entry for sale online. {@code available}: how many can be ordered from the branch (stock, or
     * for packages how many can be made); null when stock isn't counted.
     */
    public record ProductDto(Long id, OfferingType type, String name, String nameKm, String description, boolean hasImage,
                             int imageVersion, String unit, Long categoryId, String categoryName, BigDecimal price,
                             Long taxRateId, String taxRateName, BigDecimal taxRate, Integer available) {
    }

    public record ProductPage(List<ProductDto> items, int page, int totalPages, long totalItems) {
    }

    public record ShopLine(Long offeringId, int quantity) {
    }

    /** As the order will be: with {@code fulfilment} DELIVERY the delivery line is added as at checkout (last). */
    public record PromoRequest(Long branchId, OnlineOrder.Fulfilment fulfilment, List<ShopLine> items, String couponCode) {
    }

    public record PlaceOrderRequest(Long branchId, OnlineOrder.Fulfilment fulfilment, List<ShopLine> items, String couponCode,
                                    String contactName, String contactPhone, String address, String note,
                                    BigDecimal expectedTotal) {
    }

    public record OrderLine(String name, String nameKm, String unit, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                            BigDecimal discount, BigDecimal paid, boolean delivery) {
    }

    /** A payment the customer can make: the KHQR code while it waits for payment. */
    public record PaymentView(PaymentStatus status, BigDecimal amount, String currency, BigDecimal currencyAmount,
                              String khqrPayload, Instant expiresAt, Instant paidAt) {
        static PaymentView from(PaymentDto p) {
            return new PaymentView(p.status(), p.amount(), p.currency(), p.currencyAmount(), p.khqrPayload(), p.expiresAt(), p.paidAt());
        }
    }

    /** An online order as its customer sees it. */
    public record OrderView(String orderNumber, OrderStatus orderStatus, OnlineOrder.Status status, OnlineOrder.Fulfilment fulfilment,
                            Instant createdAt, String branchName, String branchAddress, String contactName, String contactPhone,
                            String address, String note, List<OrderLine> items, BigDecimal subtotal, BigDecimal discount,
                            BigDecimal taxAmount, BigDecimal taxIncluded, String taxLabel, String taxMode, BigDecimal total,
                            BigDecimal refundedTotal, String currency, String couponCode, int pointsEarned, PaymentView payment) {
    }

    /** {@code accessKey}: for a guest, the key of the private link to follow the order (shown once). */
    public record PlacedOrder(OrderView order, String accessKey) {
    }

    public record OrderSummary(String orderNumber, OrderStatus orderStatus, OnlineOrder.Status status,
                               OnlineOrder.Fulfilment fulfilment, Instant createdAt, BigDecimal total, String currency, int items) {
    }

    private final ShopSettingsService settingsService;
    private final ShopActor actor;
    private final ShopThrottle throttle;
    private final ShopPaymentMode paymentMode;
    private final OnlineOrderRepository onlineOrders;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final OfferingRepository offerings;
    private final OfferingService offeringService;
    private final PromotionService promotions;
    private final BranchRepository branches;
    private final CurrencyService currencies;
    private final SettingsService settings;
    private final TaxRateRepository taxRates;
    private final TransactionTemplate tx;

    public ShopService(ShopSettingsService settingsService, ShopActor actor, ShopThrottle throttle, ShopPaymentMode paymentMode,
                       OnlineOrderRepository onlineOrders,
                       OrderRepository orders, OrderService orderService, OfferingRepository offerings,
                       OfferingService offeringService, PromotionService promotions, BranchRepository branches,
                       CurrencyService currencies, SettingsService settings, TaxRateRepository taxRates,
                       PlatformTransactionManager txManager) {
        this.settingsService = settingsService;
        this.actor = actor;
        this.throttle = throttle;
        this.paymentMode = paymentMode;
        this.onlineOrders = onlineOrders;
        this.orders = orders;
        this.orderService = orderService;
        this.offerings = offerings;
        this.offeringService = offeringService;
        this.promotions = promotions;
        this.branches = branches;
        this.currencies = currencies;
        this.settings = settings;
        this.taxRates = taxRates;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- the store and catalog

    @Transactional(readOnly = true)
    public StoreDto store() {
        ShopSettings s = settingsService.load();
        var company = settings.current();
        var tax = company.tax();
        List<BranchDto> pickup = s.isPickupEnabled()
                ? branches.findAll().stream().filter(Branch::isActive).filter(this::sells)
                        .sorted(Comparator.comparing(Branch::getName))
                        .map(b -> new BranchDto(b.getId(), b.getName(), b.getAddress())).toList()
                : List.of();
        DeliveryDto delivery = deliveryDto(s);
        return new StoreDto(company.name(), company.hasLogo(), company.logoVersion(), currencies.base().code(),
                currencies.active().values().stream()
                        .map(m -> new CurrencyDto(m.code(), m.symbol(), m.decimals(), m.rate(), m.base())).toList(),
                tax.mode().name(), tax.label(), tax.scope(), taxRates.findByDefaultRateTrue().map(TaxRate::getId).orElse(null),
                company.timezone().getId(), s.isEnabled() && !paymentMode.blocked() && (!pickup.isEmpty() || delivery.enabled()),
                s.isPickupEnabled() && !pickup.isEmpty(), pickup, delivery, paymentMode.customersMaySimulate());
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        return offerings.findOnlineCategories().stream().map(c -> new CategoryDto(c.getId(), c.getName())).toList();
    }

    /** What the branch sells online (any pickup branch, or the delivery branch). */
    @Transactional(readOnly = true)
    public ProductPage products(Long branchId, String q, Long categoryId, int page, int size) {
        Storage storage = storageFor(branchId);
        String term = q == null || q.isBlank() ? null : q.trim();
        Page<Long> ids = offerings.findOnlineIds(term, categoryId, PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 60)));
        return new ProductPage(load(ids.getContent(), storage), ids.getNumber(), ids.getTotalPages(), ids.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductDto product(Long id, Long branchId) {
        List<ProductDto> found = load(List.of(id), storageFor(branchId));
        if (found.isEmpty()) {
            throw ApiException.notFound("Product");
        }
        return found.getFirst();
    }

    /** What promotions and a coupon code take off a cart (nothing is reserved); as at the till. */
    @Transactional(readOnly = true)
    public EvaluateResponse promotions(PromoRequest req, CustomerAccount account, String ip) {
        throttle.check(ShopThrottle.Limit.PROMOTIONS, ip, Instant.now());
        ShopSettings s = settingsService.load();
        Branch branch = orderBranch(req.branchId(), req.fulfilment(), s);
        List<CartLine> lines = cartLines(req.items(), branch, s, req.fulfilment() == OnlineOrder.Fulfilment.DELIVERY);
        return promotions.evaluate(new EvaluateRequest(lines, account == null ? null : account.customerIdForOrders(),
                blank(req.couponCode()) ? null : req.couponCode().trim()), actor.at(branch.getId()));
    }

    // ---------------------------------------------------------------- orders

    /**
     * Places an order and its KHQR payment. The delivery line is added here when delivery isn't free;
     * the client works it out the same way, and the total it shows must match.
     */
    @Transactional
    public PlacedOrder place(PlaceOrderRequest req, CustomerAccount account, String ip) {
        ShopSettings s = settingsService.load();
        if (!s.isEnabled() || paymentMode.blocked()) {
            throw ApiException.conflict("The online shop is closed right now.");
        }
        if (req.fulfilment() == null || req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("Choose pickup or delivery and add something to the cart.");
        }
        if (req.expectedTotal() == null) {
            // The customer must have seen what they will pay.
            throw ApiException.badRequest("Reload the cart and try again.");
        }
        String name = blank(req.contactName()) ? null : req.contactName().trim();
        if (name == null || name.length() > 100) {
            throw ApiException.badRequest("Enter your name (up to 100 characters).");
        }
        String phone = ShopKeys.phone(req.contactPhone());
        String address = blank(req.address()) ? null : req.address().trim();
        if (req.fulfilment() == OnlineOrder.Fulfilment.DELIVERY && (address == null || address.length() > 255)) {
            throw ApiException.badRequest("Enter the delivery address (up to 255 characters).");
        }
        if (req.note() != null && req.note().length() > 255) {
            throw ApiException.badRequest("Keep the note to 255 characters.");
        }
        if (onlineOrders.countUnpaidForPhone(phone) >= MAX_UNPAID_PER_PHONE) {
            throw ApiException.conflict("You have orders waiting for payment. Pay or let them expire before ordering again.");
        }
        throttle.check(ShopThrottle.Limit.ORDER, ip, Instant.now());
        Branch branch = orderBranch(req.branchId(), req.fulfilment(), s);
        List<CartLine> lines = cartLines(req.items(), branch, s, req.fulfilment() == OnlineOrder.Fulfilment.DELIVERY);
        // Only a confirmed account's own customer record gets the points and member offers.
        Long customerId = account == null ? null : account.customerIdForOrders();
        String note = (req.fulfilment() == OnlineOrder.Fulfilment.DELIVERY ? "Online delivery" : "Online pickup")
                + (blank(req.note()) ? "" : ": " + req.note().trim());
        OrderDto placed = orderService.checkout(new CheckoutRequest(lines, null, null, PaymentMethod.KHQR, null, null, null,
                note.length() > 255 ? note.substring(0, 255) : note, name, customerId, null, req.expectedTotal(),
                blank(req.couponCode()) ? null : req.couponCode().trim(), null), actor.at(branch.getId()));

        String key = ShopKeys.newKey();
        OnlineOrder online = new OnlineOrder();
        online.setOrder(orders.getReferenceById(placed.id()));
        online.setFulfilment(req.fulfilment());
        online.setAccount(account);
        online.setContactName(name);
        online.setContactPhone(phone);
        online.setAddress(req.fulfilment() == OnlineOrder.Fulfilment.DELIVERY ? address : null);
        online.setAccessKeyHash(ShopKeys.hash(key));
        onlineOrders.saveAndFlush(online);
        return new PlacedOrder(view(online, placed), account == null ? key : null);
    }

    /** The order for its account, or for whoever has its private link; anyone else gets "not found". */
    public OrderView order(String number, String key, CustomerAccount account) {
        return tx.execute(s -> {
            OnlineOrder online = owned(number, key, account);
            return view(online, OrderDto.from(orders.findWithItemsById(online.getOrderId()).orElseThrow()));
        });
    }

    /** Asks whether the KHQR payment has arrived (and expires it when its time is up). Payments settle in their own transactions. */
    public OrderView checkPayment(String number, String key, CustomerAccount account, String ip) {
        throttle.check(ShopThrottle.Limit.PAYMENT_CHECK, ip, Instant.now());
        Long paymentId = pendingPayment(number, key, account);
        if (paymentId != null) {
            orderService.refreshPayment(paymentId);
        }
        return order(number, key, account);
    }

    /** Test payments in development only (ShopPaymentMode): marks the payment as paid, as the till's "Simulate customer payment". */
    public OrderView simulatePayment(String number, String key, CustomerAccount account) {
        if (!paymentMode.customersMaySimulate()) {
            throw ApiException.badRequest("Simulated payments are turned off");
        }
        Long paymentId = pendingPayment(number, key, account);
        if (paymentId == null) {
            throw ApiException.conflict("This order is not waiting for payment.");
        }
        orderService.simulatePayment(paymentId);
        return order(number, key, account);
    }

    /** The customer cancels an order still waiting for payment: its items go back to stock, nothing is charged. */
    public OrderView cancel(String number, String key, CustomerAccount account) {
        var target = tx.execute(s -> {
            OnlineOrder online = owned(number, key, account);
            return new Long[] {online.getOrderId(), online.getOrder().getBranch().getId()};
        });
        if (orders.findById(target[0]).map(o -> o.getStatus() != OrderStatus.PENDING_PAYMENT).orElse(true)) {
            throw ApiException.conflict("Only orders waiting for payment can be cancelled. For a refund, contact the shop.");
        }
        orderService.cancel(target[0], actor.at(target[1]));
        return order(number, key, account);
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> accountOrders(CustomerAccount account, int page) {
        return onlineOrders.findByAccount(account.getId(), PageRequest.of(Math.max(0, page), 20)).stream()
                .map(oo -> new OrderSummary(oo.getOrder().getOrderNumber(), oo.getOrder().getStatus(), oo.getStatus(),
                        oo.getFulfilment(), oo.getCreatedAt(), oo.getOrder().getTotal(), oo.getOrder().getCurrency(),
                        oo.getOrder().getItems().stream().mapToInt(i -> i.getQuantity()).sum()))
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    private Long pendingPayment(String number, String key, CustomerAccount account) {
        return tx.execute(s -> owned(number, key, account).getOrder().getPayments().stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING).map(p -> p.getId()).findFirst().orElse(null));
    }

    private OnlineOrder owned(String number, String key, CustomerAccount account) {
        OnlineOrder online = number == null ? null : onlineOrders.findByOrderNumber(number.trim()).orElse(null);
        boolean mine = online != null && ((account != null && online.getAccount() != null
                && online.getAccount().getId().equals(account.getId())) || ShopKeys.matches(key, online.getAccessKeyHash()));
        if (!mine) {
            throw ApiException.notFound("Order");
        }
        return online;
    }

    /** Pickup at an enabled branch the customer chose, or delivery from the delivery branch. */
    private Branch orderBranch(Long branchId, OnlineOrder.Fulfilment fulfilment, ShopSettings s) {
        if (fulfilment == OnlineOrder.Fulfilment.DELIVERY) {
            if (!s.isDeliveryEnabled() || s.getDeliveryBranch() == null || s.getDeliveryOffering() == null) {
                throw ApiException.badRequest("Delivery isn't available right now.");
            }
            return s.getDeliveryBranch();
        }
        if (fulfilment == OnlineOrder.Fulfilment.PICKUP && !s.isPickupEnabled()) {
            throw ApiException.badRequest("Pickup isn't available right now.");
        }
        Branch branch = branchId == null ? null : branches.findById(branchId).filter(Branch::isActive).orElse(null);
        if (branch == null || !sells(branch)) {
            throw ApiException.badRequest("Choose a branch to order from.");
        }
        return branch;
    }

    private Storage storageFor(Long branchId) {
        Branch branch = branchId == null ? null : branches.findById(branchId).filter(Branch::isActive).orElse(null);
        if (branch == null) {
            throw ApiException.notFound("Branch");
        }
        return settingsService.requireSelling(branch);
    }

    private boolean sells(Branch b) {
        try {
            settingsService.requireSelling(b);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /**
     * The cart as checkout lines: only what is sold online, at most {@link #MAX_QUANTITY} of each;
     * plus the delivery line when asked and the goods don't reach the free-delivery amount.
     */
    private List<CartLine> cartLines(List<ShopLine> items, Branch branch, ShopSettings s, boolean delivery) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw ApiException.badRequest("The cart is empty.");
        }
        Map<Long, Offering> byId = offerings.findAllById(items.stream().map(ShopLine::offeringId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Offering::getId, Function.identity()));
        List<CartLine> lines = new ArrayList<>();
        BigDecimal goods = BigDecimal.ZERO;
        for (ShopLine l : items) {
            Offering o = l.offeringId() == null ? null : byId.get(l.offeringId());
            if (o == null || !onlineItem(o) || (s.getDeliveryOffering() != null && o.getId().equals(s.getDeliveryOffering().getId()))) {
                throw ApiException.badRequest("Something in the cart is no longer sold online. Remove it and try again.");
            }
            if (l.quantity() < 1 || l.quantity() > MAX_QUANTITY) {
                throw ApiException.badRequest("Order between 1 and " + MAX_QUANTITY + " of each item.");
            }
            lines.add(new CartLine(o.getId(), l.quantity(), null, null, null, null, null, null));
            goods = goods.add(o.getPrice().multiply(BigDecimal.valueOf(l.quantity())));
        }
        if (delivery && !freeDelivery(s, goods)) {
            lines.add(new CartLine(s.getDeliveryOffering().getId(), 1, null, null, null, null, null, null));
        }
        return lines;
    }

    static boolean freeDelivery(ShopSettings s, BigDecimal goods) {
        return s.getFreeDeliveryFrom() != null && goods.compareTo(s.getFreeDeliveryFrom()) >= 0;
    }

    private static boolean onlineItem(Offering o) {
        return o.isSellOnline() && o.isActive() && o.getType() != OfferingType.SERVICE
                && (o.getType() != OfferingType.ITEM || o.isSellable()) && o.getPricing() != PricingMode.HOURLY;
    }

    private DeliveryDto deliveryDto(ShopSettings s) {
        Offering d = s.getDeliveryOffering();
        boolean on = s.isDeliveryEnabled() && d != null && s.getDeliveryBranch() != null && s.getDeliveryBranch().isActive()
                && sells(s.getDeliveryBranch());
        if (!on) {
            return new DeliveryDto(false, null, null, null, null);
        }
        OfferingDto dto = offeringService.search(null, null, null, false, false, false, List.of(), List.of(d.getId()), Pageable.unpaged())
                .getContent().getFirst();
        return new DeliveryDto(true, d.getPrice(), s.getFreeDeliveryFrom(), s.getDeliveryBranch().getId(), product(dto));
    }

    private List<ProductDto> load(List<Long> ids, Storage storage) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, OfferingDto> found = offeringService.search(null, null, null, true, true, false, List.of(storage.getId()), ids,
                        Pageable.unpaged()).getContent().stream()
                .collect(Collectors.toMap(OfferingDto::id, Function.identity()));
        Map<Long, Offering> entities = offerings.findAllById(ids).stream().collect(Collectors.toMap(Offering::getId, Function.identity()));
        return ids.stream().filter(found::containsKey).filter(id -> onlineItem(entities.get(id)))
                .map(id -> product(found.get(id))).toList();
    }

    private static ProductDto product(OfferingDto o) {
        Integer available = o.type() == OfferingType.PACKAGE
                ? (o.packageInfo() == null ? null : o.packageInfo().available())
                : o.stock();
        return new ProductDto(o.id(), o.type(), o.name(), o.nameKm(), o.description(), o.hasImage(), o.imageVersion(), o.unit(),
                o.categoryId(), o.categoryName(), o.price(), o.taxRateId(), o.taxRateName(), o.taxRate(),
                available == null ? null : Math.max(0, available));
    }

    private OrderView view(OnlineOrder online, OrderDto o) {
        ShopSettings s = settingsService.load();
        Long deliveryId = s.getDeliveryOffering() == null ? null : s.getDeliveryOffering().getId();
        List<OrderLine> lines = o.items().stream().map(i -> new OrderLine(i.name(), i.nameKm(), i.unit(), i.quantity(), i.unitPrice(),
                i.lineTotal(), i.lineDiscount(), i.linePaid(), i.offeringId() != null && i.offeringId().equals(deliveryId))).toList();
        BigDecimal discount = o.promoDiscount().add(o.itemDiscount()).add(o.orderPromoDiscount()).add(o.discountAmount())
                .add(o.pointsDiscount());
        PaymentView payment = o.payments().stream().filter(p -> p.method() == PaymentMethod.KHQR)
                .max(Comparator.comparing(PaymentDto::id)).map(PaymentView::from).orElse(null);
        Branch branch = online.getOrder().getBranch();
        String userNote = o.note() == null ? null : o.note().replaceFirst("^Online (pickup|delivery)(: )?", "");
        return new OrderView(o.orderNumber(), o.status(), online.getStatus(), online.getFulfilment(), o.createdAt(), o.branchName(),
                branch.getAddress(), online.getContactName(), online.getContactPhone(), online.getAddress(),
                blank(userNote) ? null : userNote, lines, o.subtotal(), discount, o.taxAmount(), o.taxIncluded(), o.taxLabel(),
                o.taxMode().name(), o.total(), o.refundedTotal(), o.currency(), o.couponCode(), o.pointsEarned(), payment);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
