package com.pos.purchasing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.access.Permission;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.UnitConversion;
import com.pos.common.ApiException;
import com.pos.inventory.BranchContext;
import com.pos.inventory.MovementType;
import com.pos.inventory.StockService;
import com.pos.inventory.Storage;
import com.pos.inventory.StorageRepository;
import com.pos.purchasing.PurchasingDtos.InvoiceDto;
import com.pos.purchasing.PurchasingDtos.InvoiceRequest;
import com.pos.purchasing.PurchasingDtos.LineDto;
import com.pos.purchasing.PurchasingDtos.LineRequest;
import com.pos.purchasing.PurchasingDtos.OrderDto;
import com.pos.purchasing.PurchasingDtos.OrderRequest;
import com.pos.purchasing.PurchasingDtos.OrderSummaryDto;
import com.pos.purchasing.PurchasingDtos.PayablesSummary;
import com.pos.purchasing.PurchasingDtos.PaymentDto;
import com.pos.purchasing.PurchasingDtos.PaymentRequest;
import com.pos.purchasing.PurchasingDtos.ReceiptDto;
import com.pos.purchasing.PurchasingDtos.ReceiptLineDto;
import com.pos.purchasing.PurchasingDtos.ReceiveRequest;
import com.pos.purchasing.PurchasingDtos.VendorDto;
import com.pos.purchasing.PurchasingDtos.VendorRequest;
import com.pos.settings.SettingsService;

/**
 * Vendors, purchase orders, deliveries, vendor invoices and payments.
 * <p>An order is drafted, placed with the vendor, then received in one or more deliveries; each
 * delivery adds its stock to the order's storage (in base units, history type PURCHASE) and can
 * update the catalog cost. Vendor invoices record what is owed, with input VAT, and payments pay
 * them off. Orders belong to a branch: people without "All branches" work on their own branch's.
 */
@Service
public class PurchasingService {

    private static final EnumSet<PurchaseStatus> OPEN = EnumSet.of(PurchaseStatus.DRAFT, PurchaseStatus.ORDERED,
            PurchaseStatus.PARTIALLY_RECEIVED);

    private final VendorRepository vendors;
    private final PurchaseOrderRepository orders;
    private final VendorInvoiceRepository invoices;
    private final OfferingRepository offerings;
    private final StorageRepository storages;
    private final UserRepository users;
    private final StockService stock;
    private final BranchContext branches;
    private final AuditService audit;
    private final SettingsService settings;

    public PurchasingService(VendorRepository vendors, PurchaseOrderRepository orders, VendorInvoiceRepository invoices,
                             OfferingRepository offerings, StorageRepository storages, UserRepository users, StockService stock,
                             BranchContext branches, AuditService audit, SettingsService settings) {
        this.vendors = vendors;
        this.orders = orders;
        this.invoices = invoices;
        this.offerings = offerings;
        this.storages = storages;
        this.users = users;
        this.stock = stock;
        this.branches = branches;
        this.audit = audit;
        this.settings = settings;
    }

    // ---------------------------------------------------------------------------------- vendors

    @Transactional(readOnly = true)
    public List<VendorDto> vendors() {
        Map<Long, BigDecimal> owed = new HashMap<>();
        for (Object[] row : invoices.balances()) {
            owed.put((Long) row[0], (BigDecimal) row[1]);
        }
        return vendors.findAllByOrderByNameAsc().stream()
                .map(v -> toDto(v, owed.getOrDefault(v.getId(), BigDecimal.ZERO))).toList();
    }

    @Transactional
    public VendorDto createVendor(VendorRequest req) {
        String code = req.code().trim().toUpperCase();
        if (vendors.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("Vendor code " + code + " is already used");
        }
        Vendor v = new Vendor();
        v.setCode(code);
        apply(v, req);
        vendors.saveAndFlush(v);
        audit.log(AuditAction.VENDOR_CREATED, "Vendor", v.getId(), "Added vendor " + v.getName() + " (" + code + ")");
        return toDto(v, BigDecimal.ZERO);
    }

    @Transactional
    public VendorDto updateVendor(Long id, VendorRequest req) {
        Vendor v = vendor(id);
        String code = req.code().trim().toUpperCase();
        if (!code.equalsIgnoreCase(v.getCode()) && vendors.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("Vendor code " + code + " is already used");
        }
        Changes changes = new Changes()
                .add("Code", v.getCode(), code)
                .add("Name", v.getName(), req.name().trim())
                .add("Contact", v.getContactName(), blank(req.contactName()))
                .add("Phone", v.getPhone(), blank(req.phone()))
                .add("Email", v.getEmail(), blank(req.email()))
                .add("Address", v.getAddress(), blank(req.address()))
                .add("Tax ID", v.getTaxId(), blank(req.taxId()))
                .add("Payment terms", v.getPaymentTermsDays() + " days", req.paymentTermsDays() + " days")
                .add("Active", v.isActive() ? "yes" : "no", req.active() == null || req.active() ? "yes" : "no");
        v.setCode(code);
        apply(v, req);
        vendors.flush();
        if (!changes.isEmpty()) {
            audit.log(AuditAction.VENDOR_UPDATED, "Vendor", v.getId(), "Updated vendor " + v.getName() + ": " + changes.describe(), changes);
        }
        return toDto(v, owedBy(v.getId()));
    }

    private static void apply(Vendor v, VendorRequest req) {
        v.setName(req.name().trim());
        v.setContactName(blank(req.contactName()));
        v.setPhone(blank(req.phone()));
        v.setEmail(blank(req.email()));
        v.setAddress(blank(req.address()));
        v.setTaxId(blank(req.taxId()));
        v.setPaymentTermsDays(req.paymentTermsDays());
        v.setNote(blank(req.note()));
        v.setActive(req.active() == null || req.active());
    }

    private VendorDto toDto(Vendor v, BigDecimal owed) {
        return new VendorDto(v.getId(), v.getCode(), v.getName(), v.getContactName(), v.getPhone(), v.getEmail(),
                v.getAddress(), v.getTaxId(), v.getPaymentTermsDays(), v.getNote(), v.isActive(), owed,
                orders.countByVendorIdAndStatusIn(v.getId(), OPEN));
    }

    private BigDecimal owedBy(Long vendorId) {
        for (Object[] row : invoices.balances()) {
            if (row[0].equals(vendorId)) {
                return (BigDecimal) row[1];
            }
        }
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------------- purchase orders

    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> search(AuthUser user, Long branchId, PurchaseStatus status, Long vendorId, String q,
                                        Pageable pageable) {
        return orders.search(branches.viewScope(user, branchId), status, vendorId, blank(q), pageable).map(p ->
                new OrderSummaryDto(p.getId(), p.getPoNumber(), p.getStatus(), p.getVendor().getId(), p.getVendor().getName(),
                        p.getBranch().getName(), p.getTotal(), p.getExpectedDate(), p.getCreatedAt(), p.getLines().size(),
                        (int) p.getLines().stream().filter(l -> l.remaining() > 0).count()));
    }

    @Transactional(readOnly = true)
    public OrderDto get(Long id, AuthUser user) {
        PurchaseOrder po = order(id);
        requireBranch(user, po);
        return toDto(po);
    }

    @Transactional
    public OrderDto create(OrderRequest req, AuthUser user) {
        PurchaseOrder po = new PurchaseOrder();
        po.setCreatedBy(users.getReferenceById(user.id()));
        fill(po, req, user);
        orders.saveAndFlush(po);
        po.setPoNumber(String.format("PO-%05d", po.getId()));
        audit.log(AuditAction.PO_CREATED, "PurchaseOrder", po.getId(), "Drafted " + po.getPoNumber() + " for "
                + po.getVendor().getName() + " (" + po.getLines().size() + " lines, " + po.getTotal() + ")");
        return toDto(po);
    }

    /** Only drafts can change; a placed order is cancelled and re-drafted instead. */
    @Transactional
    public OrderDto update(Long id, OrderRequest req, AuthUser user) {
        PurchaseOrder po = lock(id);
        requireBranch(user, po);
        if (po.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.conflict("Only draft orders can be changed");
        }
        BigDecimal before = po.getTotal();
        po.getLines().clear();
        orders.flush();
        fill(po, req, user);
        orders.flush();
        audit.log(AuditAction.PO_UPDATED, "PurchaseOrder", po.getId(), "Updated " + po.getPoNumber() + " ("
                + po.getLines().size() + " lines, " + before + " → " + po.getTotal() + ")");
        return toDto(po);
    }

    private void fill(PurchaseOrder po, OrderRequest req, AuthUser user) {
        Vendor vendor = vendor(req.vendorId());
        if (!vendor.isActive() && (po.getVendor() == null || !po.getVendor().getId().equals(vendor.getId()))) {
            throw ApiException.badRequest(vendor.getName() + " is not an active vendor");
        }
        Storage storage = storages.findById(req.storageId()).orElseThrow(() -> ApiException.notFound("Storage"));
        if (!storage.isActive() || !storage.getBranch().isActive()) {
            throw ApiException.badRequest(storage.getName() + " is not active");
        }
        branches.requireBranch(user, storage.getBranch().getId());
        po.setVendor(vendor);
        po.setStorage(storage);
        po.setBranch(storage.getBranch());
        po.setExpectedDate(req.expectedDate());
        po.setVendorRef(blank(req.vendorRef()));
        po.setNote(blank(req.note()));
        Map<Long, Offering> byId = offerings.findAllById(req.lines().stream().map(LineRequest::offeringId).toList()).stream()
                .collect(Collectors.toMap(Offering::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO;
        for (LineRequest l : req.lines()) {
            Offering o = byId.get(l.offeringId());
            if (o == null) {
                throw ApiException.badRequest("Something on the order doesn't exist (id " + l.offeringId() + ")");
            }
            if (!o.getType().tracksStock()) {
                throw ApiException.badRequest(o.getName() + " isn't stocked, so it can't be purchased into stock");
            }
            Long unitId = l.unitId() != null ? l.unitId() : (o.getUnit() == null ? null : o.getUnit().getId());
            int factor = unitId == null ? 1 : UnitConversion.factorOf(o, unitId);
            String unitName = unitId == null ? null
                    : factor == 1 ? o.getUnitSymbol()
                    : o.getPacks().stream().filter(p -> p.getUnit().getId().equals(unitId)).findFirst().map(p -> p.getUnit().getSymbol()).orElse(null);
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setOffering(o);
            line.setName(o.getName());
            line.setSku(o.getSku());
            line.setUnitId(unitId);
            line.setUnit(unitName);
            line.setFactor(factor);
            line.setQuantity(l.quantity());
            line.setUnitCost(l.unitCost().setScale(4, RoundingMode.HALF_UP));
            line.setLineTotal(l.unitCost().multiply(BigDecimal.valueOf(l.quantity())).setScale(2, RoundingMode.HALF_UP));
            total = total.add(line.getLineTotal());
            po.addLine(line);
        }
        po.setTotal(total);
    }

    /** Places a draft with the vendor; deliveries can then be received. */
    @Transactional
    public OrderDto place(Long id, AuthUser user) {
        PurchaseOrder po = lock(id);
        requireBranch(user, po);
        if (po.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.conflict("Only draft orders can be placed");
        }
        po.setStatus(PurchaseStatus.ORDERED);
        po.setOrderedAt(Instant.now());
        audit.log(AuditAction.PO_ORDERED, "PurchaseOrder", po.getId(), "Placed " + po.getPoNumber() + " with "
                + po.getVendor().getName() + " (" + po.getTotal() + ")");
        return toDto(po);
    }

    /** Calls off an order that nothing has arrived for. */
    @Transactional
    public OrderDto cancel(Long id, AuthUser user) {
        PurchaseOrder po = lock(id);
        requireBranch(user, po);
        if (po.getStatus() != PurchaseStatus.DRAFT && po.getStatus() != PurchaseStatus.ORDERED) {
            throw ApiException.conflict(po.getStatus() == PurchaseStatus.PARTIALLY_RECEIVED
                    ? "Part of this order has arrived; close it instead to stop waiting for the rest"
                    : "This order can't be cancelled");
        }
        po.setStatus(PurchaseStatus.CANCELLED);
        po.setClosedAt(Instant.now());
        audit.log(AuditAction.PO_CANCELLED, "PurchaseOrder", po.getId(), "Cancelled " + po.getPoNumber());
        return toDto(po);
    }

    /** Stops waiting for the rest of a partly received order. */
    @Transactional
    public OrderDto close(Long id, AuthUser user) {
        PurchaseOrder po = lock(id);
        requireBranch(user, po);
        if (po.getStatus() != PurchaseStatus.PARTIALLY_RECEIVED) {
            throw ApiException.conflict("Only partly received orders can be closed");
        }
        String shortBy = po.getLines().stream().filter(l -> l.remaining() > 0)
                .map(l -> l.remaining() + " " + (l.getUnit() == null ? "" : l.getUnit() + " ") + l.getName())
                .collect(Collectors.joining(", "));
        po.setStatus(PurchaseStatus.RECEIVED);
        po.setClosedAt(Instant.now());
        audit.log(AuditAction.PO_CLOSED, "PurchaseOrder", po.getId(), "Closed " + po.getPoNumber() + " short: " + shortBy);
        return toDto(po);
    }

    /**
     * Receives a delivery: each line's quantity (in the order line's unit, at most what is still
     * outstanding) is added to the order's storage in base units. Optionally sets the catalog cost
     * of each entry to its purchase price per base unit.
     */
    @Transactional
    public OrderDto receive(Long id, ReceiveRequest req, AuthUser user) {
        if (req.updateCost() && !user.has(Permission.CATALOG_MANAGE) && !user.has(Permission.PURCHASE_ORDER)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can't change catalog costs; untick \"update cost\"");
        }
        PurchaseOrder po = lock(id);
        requireBranch(user, po);
        if (!po.getStatus().receivable()) {
            throw ApiException.conflict(po.getStatus() == PurchaseStatus.DRAFT
                    ? "Place the order before receiving it" : "This order is " + po.getStatus().name().toLowerCase().replace('_', ' '));
        }
        Storage storage = po.getStorage();
        if (!storage.isActive()) {
            throw ApiException.badRequest(storage.getName() + " is not active");
        }
        Map<Long, PurchaseOrderLine> byId = po.getLines().stream().collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
        Map<PurchaseOrderLine, Integer> picked = new LinkedHashMap<>();
        for (var l : req.lines()) {
            PurchaseOrderLine line = byId.get(l.lineId());
            if (line == null) {
                throw ApiException.badRequest("That line is not part of " + po.getPoNumber());
            }
            picked.merge(line, l.quantity(), Integer::sum);
        }
        picked.forEach((line, qty) -> {
            if (qty > line.remaining()) {
                throw ApiException.badRequest("Only " + line.remaining() + " " + (line.getUnit() == null ? "" : line.getUnit() + " ")
                        + "of " + line.getName() + " are still to come");
            }
        });

        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setStorage(storage);
        receipt.setDeliveryRef(blank(req.deliveryRef()));
        receipt.setNote(blank(req.note()));
        receipt.setReceivedBy(users.getReferenceById(user.id()));
        po.addReceipt(receipt);
        picked.forEach((line, qty) -> {
            GoodsReceiptLine rl = new GoodsReceiptLine();
            rl.setGoodsReceipt(receipt);
            rl.setLine(line);
            rl.setQuantity(qty);
            rl.setBaseQuantity(Math.multiplyExact(qty, line.getFactor()));
            rl.setUnitCost(line.getUnitCost());
            receipt.getLines().add(rl);
            line.setReceivedQty(line.getReceivedQty() + qty);
        });
        boolean complete = po.getLines().stream().allMatch(l -> l.remaining() == 0);
        po.setStatus(complete ? PurchaseStatus.RECEIVED : PurchaseStatus.PARTIALLY_RECEIVED);
        if (complete) {
            po.setClosedAt(Instant.now());
        }
        orders.flush();
        receipt.setReceiptNumber(String.format("GRN-%05d", receipt.getId()));

        // Stock in, in a fixed order so concurrent deliveries can't deadlock.
        StockService.Ref ref = StockService.Ref.receipt(user.id(), receipt.getId(), receipt.getReceiptNumber() + " from "
                + po.getVendor().getName() + " (" + po.getPoNumber() + ")");
        receipt.getLines().stream().sorted((a, b) -> Long.compare(a.getLine().getOffering().getId(), b.getLine().getOffering().getId()))
                .forEach(rl -> stock.add(storage.getId(), rl.getLine().getOffering().getId(), rl.getBaseQuantity(), MovementType.PURCHASE, ref));

        StringBuilder costs = new StringBuilder();
        if (req.updateCost()) {
            for (GoodsReceiptLine rl : receipt.getLines()) {
                Offering o = rl.getLine().getOffering();
                BigDecimal perBase = rl.getUnitCost().divide(BigDecimal.valueOf(rl.getLine().getFactor()), 2, RoundingMode.HALF_UP);
                if (o.getCost() == null || o.getCost().compareTo(perBase) != 0) {
                    costs.append("; cost of ").append(o.getName()).append(' ').append(o.getCost()).append(" → ").append(perBase);
                    o.setCost(perBase);
                }
            }
        }
        audit.log(AuditAction.GOODS_RECEIVED, "PurchaseOrder", po.getId(), "Received " + receipt.getReceiptNumber() + " on "
                + po.getPoNumber() + " into " + storage.getName() + ": " + receipt.getLines().stream()
                .map(rl -> rl.getQuantity() + " " + (rl.getLine().getUnit() == null ? "" : rl.getLine().getUnit() + " ") + rl.getLine().getName())
                .collect(Collectors.joining(", ")) + costs);
        return toDto(po);
    }

    private OrderDto toDto(PurchaseOrder po) {
        BigDecimal receivedValue = po.getLines().stream()
                .map(l -> l.getUnitCost().multiply(BigDecimal.valueOf(l.getReceivedQty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new OrderDto(po.getId(), po.getPoNumber(), po.getStatus(), po.getVendor().getId(), po.getVendor().getName(),
                po.getBranch().getId(), po.getBranch().getName(), po.getStorage().getId(), po.getStorage().getName(),
                po.getExpectedDate(), po.getVendorRef(), po.getNote(), po.getTotal(), receivedValue,
                po.getCreatedBy().getFullName(), po.getCreatedAt(), po.getOrderedAt(), po.getClosedAt(),
                po.getLines().stream().map(l -> new LineDto(l.getId(), l.getOffering().getId(), l.getName(), l.getSku(), l.getUnitId(),
                        l.getUnit(), l.getFactor(), l.getQuantity(), l.getReceivedQty(), l.remaining(), l.getUnitCost(), l.getLineTotal())).toList(),
                po.getReceipts().stream().map(r -> new ReceiptDto(r.getId(), r.getReceiptNumber(), r.getStorage().getName(),
                        r.getDeliveryRef(), r.getNote(), r.getReceivedBy().getFullName(), r.getReceivedAt(),
                        r.getLines().stream().map(rl -> new ReceiptLineDto(rl.getLine().getId(), rl.getLine().getName(), rl.getLine().getUnit(),
                                rl.getQuantity(), rl.getBaseQuantity(), rl.getUnitCost())).toList())).toList(),
                invoices.findByPurchaseOrderIdOrderByIdAsc(po.getId()).stream().map(this::toDto).toList());
    }

    // ---------------------------------------------------------------------------------- invoices

    @Transactional(readOnly = true)
    public Page<InvoiceDto> invoices(Long vendorId, InvoiceStatus status, boolean overdue, String q, Pageable pageable) {
        return invoices.search(vendorId, status, overdue, blank(q), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public InvoiceDto invoice(Long id) {
        return toDto(invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice")));
    }

    @Transactional(readOnly = true)
    public PayablesSummary summary(AuthUser user) {
        BigDecimal owed = BigDecimal.ZERO;
        for (Object[] row : invoices.balances()) {
            owed = owed.add((BigDecimal) row[1]);
        }
        long unpaid = invoices.search(null, null, false, null, Pageable.unpaged()).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.UNPAID || i.getStatus() == InvoiceStatus.PARTIALLY_PAID).count();
        long open = orders.search(branches.viewScope(user, null), null, null, null, Pageable.unpaged()).stream()
                .filter(p -> p.getStatus().receivable()).count();
        return new PayablesSummary(owed, invoices.overdueTotal(), unpaid, open);
    }

    @Transactional
    public InvoiceDto recordInvoice(InvoiceRequest req, AuthUser user) {
        Vendor vendor = vendor(req.vendorId());
        String number = req.invoiceNumber().trim();
        if (invoices.existsByVendorIdAndInvoiceNumberIgnoreCase(vendor.getId(), number)) {
            throw ApiException.conflict(vendor.getName() + " invoice " + number + " is already recorded");
        }
        PurchaseOrder po = null;
        if (req.purchaseOrderId() != null) {
            po = order(req.purchaseOrderId());
            requireBranch(user, po);
            if (!po.getVendor().getId().equals(vendor.getId())) {
                throw ApiException.badRequest(po.getPoNumber() + " is from another vendor");
            }
            if (po.getStatus() == PurchaseStatus.DRAFT || po.getStatus() == PurchaseStatus.CANCELLED) {
                throw ApiException.badRequest(po.getPoNumber() + " hasn't been placed");
            }
        }
        LocalDate due = req.dueDate() != null ? req.dueDate() : req.invoiceDate().plusDays(vendor.getPaymentTermsDays());
        if (due.isBefore(req.invoiceDate())) {
            throw ApiException.badRequest("The due date is before the invoice date");
        }
        VendorInvoice i = new VendorInvoice();
        i.setVendor(vendor);
        i.setPurchaseOrder(po);
        i.setInvoiceNumber(number);
        i.setInvoiceDate(req.invoiceDate());
        i.setDueDate(due);
        i.setSubtotal(req.subtotal().setScale(2, RoundingMode.HALF_UP));
        i.setTax(req.tax().setScale(2, RoundingMode.HALF_UP));
        i.setTotal(i.getSubtotal().add(i.getTax()));
        i.setNote(blank(req.note()));
        i.setCreatedBy(users.getReferenceById(user.id()));
        if (i.getTotal().signum() == 0) {
            i.setStatus(InvoiceStatus.PAID);
        }
        invoices.saveAndFlush(i);
        audit.log(AuditAction.VENDOR_INVOICE_RECORDED, "VendorInvoice", i.getId(), "Recorded " + vendor.getName() + " invoice "
                + number + (po == null ? "" : " for " + po.getPoNumber()) + ": " + i.getTotal() + " (tax " + i.getTax() + "), due " + due);
        return toDto(i);
    }

    /** Voids an invoice recorded by mistake; only while nothing has been paid on it. */
    @Transactional
    public InvoiceDto voidInvoice(Long id, AuthUser user) {
        VendorInvoice i = invoices.lockById(id).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (i.getPurchaseOrder() != null) {
            requireBranch(user, i.getPurchaseOrder());
        }
        if (i.getPaid().signum() > 0) {
            throw ApiException.conflict("Payments were recorded on this invoice, so it can't be voided");
        }
        if (i.getStatus() == InvoiceStatus.VOID) {
            return toDto(i);
        }
        i.setStatus(InvoiceStatus.VOID);
        audit.log(AuditAction.VENDOR_INVOICE_VOIDED, "VendorInvoice", i.getId(), "Voided " + i.getVendor().getName()
                + " invoice " + i.getInvoiceNumber() + " (" + i.getTotal() + ")");
        return toDto(i);
    }

    @Transactional
    public InvoiceDto pay(Long id, PaymentRequest req, AuthUser user) {
        VendorInvoice i = invoices.lockById(id).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (i.getPurchaseOrder() != null) {
            requireBranch(user, i.getPurchaseOrder());
        }
        if (i.getStatus() == InvoiceStatus.VOID || i.getStatus() == InvoiceStatus.PAID) {
            throw ApiException.conflict("This invoice is " + i.getStatus().name().toLowerCase());
        }
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(i.balance()) > 0) {
            throw ApiException.badRequest("That is more than the " + i.balance() + " still owed");
        }
        if (req.paidOn().isAfter(today())) {
            throw ApiException.badRequest("The payment date is in the future");
        }
        VendorPayment p = new VendorPayment();
        p.setInvoice(i);
        p.setAmount(amount);
        p.setMethod(req.method());
        p.setPaidOn(req.paidOn());
        p.setReference(blank(req.reference()));
        p.setNote(blank(req.note()));
        p.setCreatedBy(users.getReferenceById(user.id()));
        i.getPayments().add(p);
        i.setPaid(i.getPaid().add(amount));
        i.setStatus(i.getPaid().compareTo(i.getTotal()) >= 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        invoices.flush();
        audit.log(AuditAction.VENDOR_PAYMENT_RECORDED, "VendorInvoice", i.getId(), "Paid " + amount + " to " + i.getVendor().getName()
                + " on invoice " + i.getInvoiceNumber() + " by " + req.method().name().toLowerCase().replace('_', ' ')
                + (p.getReference() == null ? "" : " (" + p.getReference() + ")") + "; " + i.balance() + " left");
        return toDto(i);
    }

    private InvoiceDto toDto(VendorInvoice i) {
        PurchaseOrder po = i.getPurchaseOrder();
        return new InvoiceDto(i.getId(), i.getVendor().getId(), i.getVendor().getName(), po == null ? null : po.getId(),
                po == null ? null : po.getPoNumber(), i.getInvoiceNumber(), i.getInvoiceDate(), i.getDueDate(), i.getSubtotal(),
                i.getTax(), i.getTotal(), i.getPaid(), i.balance(), i.getStatus(), i.overdue(today()), i.getNote(),
                i.getCreatedBy().getFullName(), i.getCreatedAt(),
                i.getPayments().stream().map(p -> new PaymentDto(p.getId(), p.getAmount(), p.getMethod(), p.getPaidOn(), p.getReference(),
                        p.getNote(), p.getCreatedBy().getFullName(), p.getCreatedAt())).toList());
    }

    // ------------------------------------------------------------------------------------ helpers

    private LocalDate today() {
        return LocalDate.now(settings.current().timezone());
    }

    private void requireBranch(AuthUser user, PurchaseOrder po) {
        if (!user.allBranches() && !po.getBranch().getId().equals(user.branchId())) {
            throw ApiException.notFound("Purchase order");
        }
    }

    private Vendor vendor(Long id) {
        return vendors.findById(id).orElseThrow(() -> ApiException.notFound("Vendor"));
    }

    private PurchaseOrder order(Long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
    }

    private PurchaseOrder lock(Long id) {
        return orders.lockById(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
