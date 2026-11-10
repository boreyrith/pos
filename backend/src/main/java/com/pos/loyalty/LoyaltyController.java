package com.pos.loyalty;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.loyalty.LoyaltyService.AdjustRequest;
import com.pos.loyalty.LoyaltyService.Balance;
import com.pos.loyalty.LoyaltyService.Overview;
import com.pos.loyalty.LoyaltyService.Rules;
import com.pos.loyalty.LoyaltyService.RulesRequest;
import com.pos.loyalty.LoyaltyService.TransactionDto;

import jakarta.validation.Valid;

/**
 * Loyalty points. The sell screen reads the rules and a customer's balance with "Sell"; the
 * overview and history need "View marketing" (or "View customers" for one customer's history);
 * changing the rules or a balance needs "Manage loyalty".
 */
@RestController
@RequestMapping("/loyalty")
public class LoyaltyController {

    private final LoyaltyService service;

    public LoyaltyController(LoyaltyService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAnyAuthority('SALES_SELL','MARKETING_VIEW','CUSTOMERS_VIEW')")
    public Rules rules() {
        return service.rules();
    }

    @PutMapping("/rules")
    @PreAuthorize("hasAuthority('LOYALTY_MANAGE')")
    public Rules updateRules(@Valid @RequestBody RulesRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.updateRules(req, user);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/activity")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public List<TransactionDto> recent(@RequestParam(defaultValue = "30") int limit) {
        return service.recent(Math.clamp(limit, 1, 200));
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAnyAuthority('SALES_SELL','CUSTOMERS_VIEW')")
    public Balance balance(@PathVariable Long id) {
        return service.balance(id);
    }

    @GetMapping("/customers/{id}/history")
    @PreAuthorize("hasAnyAuthority('CUSTOMERS_VIEW','MARKETING_VIEW')")
    public List<TransactionDto> history(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        return service.history(id, Math.clamp(limit, 1, 200));
    }

    @PostMapping("/customers/{id}/adjust")
    @PreAuthorize("hasAuthority('LOYALTY_MANAGE')")
    public Balance adjust(@PathVariable Long id, @Valid @RequestBody AdjustRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.adjust(id, req, user);
    }
}
