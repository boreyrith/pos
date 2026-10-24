package com.pos.audit;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.PageResponse;
import com.pos.settings.SettingsService;

/** The audit log, newest first. Admins only. {@code from}/{@code to} are inclusive days. */
@RestController
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AuditController {

    /** {@code details} is JSON text, e.g. {"changes":[{"field":"Price","from":"2.5","to":"2.75"}]}. */
    public record AuditDto(Long id, Instant createdAt, Long userId, String username, Long branchId, AuditAction action,
                           AuditAction.Category category, String entityType, String entityId, String summary,
                           String details, String ip, String userAgent) {
        static AuditDto from(AuditLog a) {
            return new AuditDto(a.getId(), a.getCreatedAt(), a.getUserId(), a.getUsername(), a.getBranchId(),
                    a.getAction(), a.getCategory(), a.getEntityType(), a.getEntityId(), a.getSummary(), a.getDetails(),
                    a.getIp(), a.getUserAgent());
        }
    }

    private final AuditLogRepository repository;
    private final SettingsService settings;

    public AuditController(AuditLogRepository repository, SettingsService settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @GetMapping("/audit-logs")
    public PageResponse<AuditDto> search(@RequestParam(required = false) Long userId,
                                         @RequestParam(required = false) AuditAction.Category category,
                                         @RequestParam(required = false) AuditAction action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) String entityId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        var zone = settings.current().timezone();
        Instant start = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "id"));
        String term = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.of(repository.search(userId, category, action, entityType, entityId, start, end, term, pageable),
                AuditDto::from);
    }
}
