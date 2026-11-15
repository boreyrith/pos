package com.pos.shop;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.branch.Branch;
import com.pos.branch.BranchRepository;
import com.pos.catalog.CatalogDtos.OfferingRequest;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.OfferingService;
import com.pos.catalog.PricingMode;
import com.pos.common.ApiException;
import com.pos.common.Money;
import com.pos.common.OfferingType;
import com.pos.inventory.Storage;
import com.pos.inventory.StorageRepository;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** The online shop's settings (Settings > Online shop): open or closed, pickup, delivery and its fee. */
@Service
public class ShopSettingsService {

    /** {@code deliveryFee}: the Delivery service's price (null until delivery is first set up). */
    public record ShopSettingsDto(boolean enabled, boolean pickupEnabled, boolean deliveryEnabled, Long deliveryBranchId,
                                  BigDecimal deliveryFee, BigDecimal freeDeliveryFrom, Long deliveryOfferingId,
                                  String updatedBy, Instant updatedAt) {
    }

    public record ShopSettingsRequest(boolean enabled, boolean pickupEnabled, boolean deliveryEnabled, Long deliveryBranchId,
                                      @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal deliveryFee,
                                      @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal freeDeliveryFrom) {
    }

    private final ShopSettingsRepository repository;
    private final BranchRepository branches;
    private final StorageRepository storages;
    private final OfferingRepository offerings;
    private final OfferingService offeringService;
    private final UserRepository users;
    private final AuditService audit;
    private final ShopPaymentMode paymentMode;

    public ShopSettingsService(ShopSettingsRepository repository, BranchRepository branches, StorageRepository storages,
                               OfferingRepository offerings, OfferingService offeringService, UserRepository users,
                               AuditService audit, ShopPaymentMode paymentMode) {
        this.repository = repository;
        this.branches = branches;
        this.storages = storages;
        this.offerings = offerings;
        this.offeringService = offeringService;
        this.users = users;
        this.audit = audit;
        this.paymentMode = paymentMode;
    }

    @Transactional(readOnly = true)
    public ShopSettingsDto get() {
        return toDto(load());
    }

    ShopSettings load() {
        return repository.findWithDetailsById(ShopSettings.ID).orElseThrow();
    }

    @Transactional
    public ShopSettingsDto update(ShopSettingsRequest req, AuthUser user) {
        ShopSettings s = load();
        if (req.enabled() && paymentMode.blocked()) {
            throw ApiException.badRequest("Switch KHQR to live payments (Settings > Taxes & payments) before opening the online shop: "
                    + "with test payments, anyone could mark an order paid.");
        }
        if (req.enabled() && !req.pickupEnabled() && !req.deliveryEnabled()) {
            throw ApiException.badRequest("Turn on pickup, delivery or both before opening the shop.");
        }
        Branch deliveryBranch = null;
        if (req.deliveryBranchId() != null) {
            deliveryBranch = branches.findById(req.deliveryBranchId()).orElseThrow(() -> ApiException.notFound("Branch"));
        }
        if (req.deliveryEnabled()) {
            if (deliveryBranch == null) {
                throw ApiException.badRequest("Choose the branch that sends out deliveries.");
            }
            requireSelling(deliveryBranch);
        }
        BigDecimal oldFee = s.getDeliveryOffering() == null ? null : s.getDeliveryOffering().getPrice();
        Changes changes = new Changes()
                .add("Shop open", yes(s.isEnabled()), yes(req.enabled()))
                .add("Pickup", yes(s.isPickupEnabled()), yes(req.pickupEnabled()))
                .add("Delivery", yes(s.isDeliveryEnabled()), yes(req.deliveryEnabled()))
                .add("Delivery from", s.getDeliveryBranch() == null ? null : s.getDeliveryBranch().getName(),
                        deliveryBranch == null ? null : deliveryBranch.getName())
                .add("Delivery fee", oldFee == null ? null : oldFee.stripTrailingZeros().toPlainString(),
                        req.deliveryFee().stripTrailingZeros().toPlainString())
                .add("Free delivery from", s.getFreeDeliveryFrom() == null ? null : s.getFreeDeliveryFrom().stripTrailingZeros().toPlainString(),
                        req.freeDeliveryFrom() == null ? null : req.freeDeliveryFrom().stripTrailingZeros().toPlainString());
        s.setEnabled(req.enabled());
        s.setPickupEnabled(req.pickupEnabled());
        s.setDeliveryEnabled(req.deliveryEnabled());
        s.setDeliveryBranch(deliveryBranch);
        s.setFreeDeliveryFrom(req.freeDeliveryFrom() == null ? null : Money.round(req.freeDeliveryFrom()));
        // The fee is the price of the Delivery service, so a delivery is a line like any other.
        if (s.getDeliveryOffering() == null) {
            if (req.deliveryEnabled()) {
                s.setDeliveryOffering(createDeliveryService(Money.round(req.deliveryFee()), user));
            }
        } else if (s.getDeliveryOffering().getPrice().compareTo(req.deliveryFee()) != 0) {
            s.getDeliveryOffering().setPrice(Money.round(req.deliveryFee()));
        }
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
        repository.saveAndFlush(s);
        if (!changes.isEmpty()) {
            audit.log(AuditAction.SHOP_SETTINGS_UPDATED, "Settings", "shop", "Online shop: " + changes.describe(), changes);
        }
        return toDto(s);
    }

    /** A branch can take online orders only with an active selling storage. */
    Storage requireSelling(Branch branch) {
        Storage storage = storages.findByBranchIdAndSellingTrue(branch.getId())
                .filter(st -> st.isActive() && branch.isActive())
                .orElseThrow(() -> ApiException.badRequest(branch.getName() + " has no active selling storage, so it can't take online orders."));
        return storage;
    }

    private Offering createDeliveryService(BigDecimal fee, AuthUser user) {
        var created = offeringService.create(new OfferingRequest(OfferingType.SERVICE, null, null, null, "Delivery", "ការដឹកជញ្ជូន",
                "Delivery of an online order (Settings > Online shop sets the fee).", null, null, null, fee, PricingMode.FIXED,
                null, BigDecimal.ZERO, null, null, null, true, null, null, null, false), user);
        Offering delivery = offerings.findById(created.id()).orElseThrow();
        // Only the shop adds it to a sale: not on the till's sell screen, quick picks or top sellers.
        delivery.setTillHidden(true);
        return delivery;
    }

    private ShopSettingsDto toDto(ShopSettings s) {
        Offering d = s.getDeliveryOffering();
        return new ShopSettingsDto(s.isEnabled(), s.isPickupEnabled(), s.isDeliveryEnabled(),
                s.getDeliveryBranch() == null ? null : s.getDeliveryBranch().getId(), d == null ? null : d.getPrice(),
                s.getFreeDeliveryFrom(), d == null ? null : d.getId(),
                s.getUpdatedBy() == null ? null : s.getUpdatedBy().getFullName(), s.getUpdatedAt());
    }

    private static String yes(boolean b) {
        return b ? "on" : "off";
    }
}
