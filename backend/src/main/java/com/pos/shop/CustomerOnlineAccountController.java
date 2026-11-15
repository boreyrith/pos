package com.pos.shop;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.common.ApiException;

/**
 * A customer's online-shop account, for staff (Customers > a customer): confirm it once they have
 * checked at the counter that the person owns the phone, or remove it (someone else registered the
 * number, or the customer forgot the password: they then sign up again). Its orders stay, as guest orders.
 */
@RestController
public class CustomerOnlineAccountController {

    public record OnlineAccountDto(String phone, boolean verified, Instant createdAt, Instant lastLoginAt, long orders) {
    }

    private final CustomerAccountRepository accounts;
    private final CustomerSessionRepository sessions;
    private final OnlineOrderRepository onlineOrders;
    private final AuditService audit;

    public CustomerOnlineAccountController(CustomerAccountRepository accounts, CustomerSessionRepository sessions,
                                           OnlineOrderRepository onlineOrders, AuditService audit) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.onlineOrders = onlineOrders;
        this.audit = audit;
    }

    /** 204 when the customer has no online account. */
    @GetMapping("/customers/{id}/online-account")
    @PreAuthorize("hasAuthority('CUSTOMERS_VIEW')")
    @Transactional(readOnly = true)
    public ResponseEntity<OnlineAccountDto> get(@PathVariable Long id) {
        return accounts.findByCustomerId(id)
                .map(a -> ResponseEntity.ok(new OnlineAccountDto(a.getPhone(), a.isVerified(), a.getCreatedAt(), a.getLastLoginAt(),
                        onlineOrders.countByAccountId(a.getId()))))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/customers/{id}/online-account/verify")
    @PreAuthorize("hasAuthority('CUSTOMERS_MANAGE')")
    @Transactional
    public OnlineAccountDto verify(@PathVariable Long id) {
        CustomerAccount a = accounts.findByCustomerId(id).orElseThrow(() -> ApiException.notFound("Online account"));
        if (!a.isVerified()) {
            a.setVerified(true);
            audit.log(AuditAction.CUSTOMER_ACCOUNT_VERIFIED, "Customer", id,
                    "Confirmed the online account " + a.getPhone() + " for " + a.getCustomer().getName());
        }
        return new OnlineAccountDto(a.getPhone(), true, a.getCreatedAt(), a.getLastLoginAt(), onlineOrders.countByAccountId(a.getId()));
    }

    /** Signs it out everywhere and removes it; its orders become guest orders (still on the customer's history if confirmed). */
    @DeleteMapping("/customers/{id}/online-account")
    @PreAuthorize("hasAuthority('CUSTOMERS_MANAGE')")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        CustomerAccount a = accounts.findByCustomerId(id).orElseThrow(() -> ApiException.notFound("Online account"));
        sessions.deleteByAccount(a.getId());
        onlineOrders.detachAccount(a.getId());
        accounts.delete(a);
        audit.log(AuditAction.CUSTOMER_ACCOUNT_REMOVED, "Customer", id,
                "Removed the online account " + a.getPhone() + " of " + a.getCustomer().getName());
        return ResponseEntity.noContent().build();
    }
}
