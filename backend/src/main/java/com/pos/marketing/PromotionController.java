package com.pos.marketing;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.marketing.PromotionDtos.CouponBatchRequest;
import com.pos.marketing.PromotionDtos.CouponDto;
import com.pos.marketing.PromotionDtos.CouponRequest;
import com.pos.marketing.PromotionDtos.CouponUpdate;
import com.pos.marketing.PromotionDtos.EvaluateRequest;
import com.pos.marketing.PromotionDtos.EvaluateResponse;
import com.pos.marketing.PromotionDtos.PromotionDto;
import com.pos.marketing.PromotionDtos.PromotionRequest;

import jakarta.validation.Valid;

/** Promotions and coupon codes ("View marketing" to see, "Manage promotions" to change); the sell screen prices carts with "Sell". */
@RestController
@RequestMapping("/promotions")
public class PromotionController {

    private final PromotionService service;

    public PromotionController(PromotionService service) {
        this.service = service;
    }

    /** What promotions take off a cart, and whether its coupon works. Nothing is reserved until checkout. */
    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('SALES_SELL')")
    public EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.evaluate(req, user);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public List<PromotionDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public PromotionDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    public PromotionDto create(@Valid @RequestBody PromotionRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.create(req, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    public PromotionDto update(@PathVariable Long id, @Valid @RequestBody PromotionRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}/codes")
    @PreAuthorize("hasAuthority('MARKETING_VIEW')")
    public List<CouponDto> codes(@PathVariable Long id) {
        return service.codes(id);
    }

    @PostMapping("/{id}/codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    public CouponDto addCode(@PathVariable Long id, @Valid @RequestBody CouponRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.addCode(id, req, user);
    }

    @PostMapping("/{id}/codes/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    public List<CouponDto> generate(@PathVariable Long id, @Valid @RequestBody CouponBatchRequest req,
                                    @AuthenticationPrincipal AuthUser user) {
        return service.generate(id, req, user);
    }

    @PutMapping("/{id}/codes/{codeId}")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    public CouponDto updateCode(@PathVariable Long id, @PathVariable Long codeId, @Valid @RequestBody CouponUpdate req) {
        return service.updateCode(id, codeId, req);
    }
}
