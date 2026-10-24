package com.pos.settings;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.common.ImageTypes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Company information and system-wide settings. Managers can view; admins change them. */
@RestController
@RequestMapping("/settings/company")
public class SettingsController {

    public record CompanyDto(String name, String legalName, String taxId, String address, String phone, String email,
                             String website, String receiptFooter, String timezone, boolean hasLogo,
                             int logoVersion, String updatedBy, Instant updatedAt) {
    }

    /** Tax mode and rates are set up at /settings/tax and /tax-rates; KHQR at /settings/khqr. */
    public record CompanyRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 150) String legalName,
            @Size(max = 50) String taxId,
            @Size(max = 255) String address,
            @Size(max = 50) String phone,
            @Email @Size(max = 100) String email,
            @Size(max = 150) String website,
            @Size(max = 500) String receiptFooter,
            @NotBlank @Size(max = 50) String timezone) {
    }

    private static final long MAX_LOGO_BYTES = 512 * 1024;

    private final CompanySettingsRepository repository;
    private final SettingsService settings;
    private final UserRepository users;
    private final AuditService audit;

    public SettingsController(CompanySettingsRepository repository, SettingsService settings, UserRepository users,
                              AuditService audit) {
        this.audit = audit;
        this.repository = repository;
        this.settings = settings;
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SETTINGS_VIEW')")
    public CompanyDto get() {
        settings.current();
        return toDto(load());
    }

    @PutMapping
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public CompanyDto update(@Valid @RequestBody CompanyRequest req, @AuthenticationPrincipal AuthUser user) {
        try {
            ZoneId.of(req.timezone().trim());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Unknown time zone " + req.timezone() + ". Use a name like Asia/Phnom_Penh.");
        }
        settings.current();
        CompanySettings s = load();
        Changes changes = new Changes()
                .add("Trading name", s.getName(), req.name().trim())
                .add("Legal name", s.getLegalName(), blankToNull(req.legalName()))
                .add("Tax ID", s.getTaxId(), blankToNull(req.taxId()))
                .add("Address", s.getAddress(), blankToNull(req.address()))
                .add("Phone", s.getPhone(), blankToNull(req.phone()))
                .add("Email", s.getEmail(), blankToNull(req.email()))
                .add("Website", s.getWebsite(), blankToNull(req.website()))
                .add("Receipt footer", s.getReceiptFooter(), blankToNull(req.receiptFooter()))
                .add("Time zone", s.getTimezone(), req.timezone().trim());
        s.setName(req.name().trim());
        s.setLegalName(blankToNull(req.legalName()));
        s.setTaxId(blankToNull(req.taxId()));
        s.setAddress(blankToNull(req.address()));
        s.setPhone(blankToNull(req.phone()));
        s.setEmail(blankToNull(req.email()));
        s.setWebsite(blankToNull(req.website()));
        s.setReceiptFooter(blankToNull(req.receiptFooter()));
        s.setTimezone(req.timezone().trim());
        touch(s, user);
        settings.refresh(repository.saveAndFlush(s));
        if (!changes.isEmpty()) {
            audit.log(AuditAction.SETTINGS_UPDATED, "Settings", "company", "Company settings: " + changes.describe(), changes);
        }
        return toDto(s);
    }

    /** PNG, JPEG or WebP up to 512 KB. SVG is refused because it can carry scripts. */
    @PutMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public CompanyDto uploadLogo(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthUser user)
            throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("Choose an image");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw ApiException.badRequest("The logo must be 512 KB or smaller");
        }
        byte[] bytes = file.getBytes();
        String type = sniffImageType(bytes);
        if (type == null) {
            throw ApiException.badRequest("The logo must be a PNG, JPEG or WebP image");
        }
        settings.current();
        CompanySettings s = load();
        s.setLogo(bytes);
        s.setLogoType(type);
        s.setLogoVersion(s.getLogoVersion() + 1);
        touch(s, user);
        settings.refresh(repository.saveAndFlush(s));
        audit.log(AuditAction.LOGO_CHANGED, "Settings", "logo", "Uploaded a new logo (" + type + ", "
                + (bytes.length / 1024) + " KB)");
        return toDto(s);
    }

    @DeleteMapping("/logo")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public CompanyDto deleteLogo(@AuthenticationPrincipal AuthUser user) {
        settings.current();
        CompanySettings s = load();
        s.setLogo(null);
        s.setLogoType(null);
        s.setLogoVersion(s.getLogoVersion() + 1);
        touch(s, user);
        settings.refresh(repository.saveAndFlush(s));
        audit.log(AuditAction.LOGO_CHANGED, "Settings", "logo", "Removed the logo");
        return toDto(s);
    }

    /** Public, so receipts and the sign-in page can show it in an img tag. Versioned by ?v=. */
    @GetMapping("/logo")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> logo() {
        settings.current();
        CompanySettings s = load();
        if (s.getLogoType() == null) {
            throw ApiException.notFound("Logo");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(s.getLogoType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(s.getLogo());
    }

    private CompanySettings load() {
        return repository.findById(CompanySettings.ID).orElseThrow();
    }

    private void touch(CompanySettings s, AuthUser user) {
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
    }

    private static CompanyDto toDto(CompanySettings s) {
        return new CompanyDto(s.getName(), s.getLegalName(), s.getTaxId(), s.getAddress(), s.getPhone(), s.getEmail(),
                s.getWebsite(), s.getReceiptFooter(), s.getTimezone(), s.getLogoType() != null, s.getLogoVersion(),
                s.getUpdatedBy() == null ? null : s.getUpdatedBy().getFullName(), s.getUpdatedAt());
    }

    /** Checks the file's first bytes, not its name or the browser's claim. */
    static String sniffImageType(byte[] b) {
        return ImageTypes.sniff(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
