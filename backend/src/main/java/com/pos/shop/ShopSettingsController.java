package com.pos.shop;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.shop.ShopSettingsService.ShopSettingsDto;
import com.pos.shop.ShopSettingsService.ShopSettingsRequest;

import jakarta.validation.Valid;

/** Settings > Online shop. Managers can view; those who change company settings change these. */
@RestController
@RequestMapping("/settings/shop")
public class ShopSettingsController {

    private final ShopSettingsService service;

    public ShopSettingsController(ShopSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS_VIEW')")
    public ShopSettingsDto get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public ShopSettingsDto update(@Valid @RequestBody ShopSettingsRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.update(req, user);
    }
}
