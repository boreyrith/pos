package com.pos.shop;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.shop.OnlineOrderService.OnlineOrderDto;
import com.pos.shop.OnlineOrderService.View;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Online orders for staff: the till's Online orders page. */
@RestController
@RequestMapping("/online-orders")
public class OnlineOrderController {

    public record StatusRequest(@NotNull OnlineOrder.Status status) {
    }

    private final OnlineOrderService service;

    public OnlineOrderController(OnlineOrderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ONLINE_ORDERS')")
    public List<OnlineOrderDto> list(@RequestParam(defaultValue = "NEW") View view, @RequestParam(required = false) Long branchId,
                                     @AuthenticationPrincipal AuthUser user) {
        return service.list(view, branchId, user);
    }

    @GetMapping("/count")
    @PreAuthorize("hasAuthority('ONLINE_ORDERS')")
    public Map<String, Long> count(@AuthenticationPrincipal AuthUser user) {
        return Map.of("new", service.countNew(user));
    }

    @PostMapping("/{orderId}/status")
    @PreAuthorize("hasAuthority('ONLINE_ORDERS')")
    public OnlineOrderDto setStatus(@PathVariable Long orderId, @Valid @RequestBody StatusRequest req,
                                    @AuthenticationPrincipal AuthUser user) {
        return service.setStatus(orderId, req.status(), user);
    }
}
