package com.pos.hold;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthUser;
import com.pos.hold.HeldSaleService.HoldDto;
import com.pos.hold.HeldSaleService.HoldRequest;
import com.pos.hold.HeldSaleService.ResumeDto;

import jakarta.validation.Valid;

/** Held sales for the current branch. Selling is enough to hold, resume and void your own. */
@RestController
@RequestMapping("/held-sales")
@PreAuthorize("hasAuthority('SALES_SELL')")
public class HeldSaleController {

    private final HeldSaleService service;

    public HeldSaleController(HeldSaleService service) {
        this.service = service;
    }

    /** Open holds (held or in use), or with {@code closed=true} the latest completed, voided and expired ones. */
    @GetMapping
    public List<HoldDto> list(@RequestParam(defaultValue = "false") boolean closed, @AuthenticationPrincipal AuthUser user) {
        return service.list(user, closed);
    }

    @GetMapping("/count")
    public Map<String, Long> count(@AuthenticationPrincipal AuthUser user) {
        return Map.of("open", service.openCount(user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldDto hold(@Valid @RequestBody HoldRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.hold(req, user);
    }

    @PutMapping("/{id}")
    public HoldDto update(@PathVariable Long id, @Valid @RequestBody HoldRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.update(id, req, user);
    }

    @PostMapping("/{id}/resume")
    public ResumeDto resume(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean takeOver,
                            @AuthenticationPrincipal AuthUser user) {
        return service.resume(id, takeOver, user);
    }

    @PostMapping("/{id}/put-back")
    public HoldDto putBack(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.putBack(id, user);
    }

    public record VoidRequest(String reason) {
    }

    @PostMapping("/{id}/void")
    public HoldDto voidHold(@PathVariable Long id, @RequestBody VoidRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.voidHold(id, req.reason(), user);
    }

    /** Expires old holds and releases abandoned locks. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void housekeeping() {
        service.housekeeping();
    }
}
