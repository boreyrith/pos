package com.pos.settings;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.common.SecretBox;
import com.pos.config.AppProperties.KhqrMode;
import com.pos.payment.khqr.BakongClient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings > KHQR payments: the Bakong account customers pay into, the merchant name and city on
 * the QR code, test or live mode, the Bakong API address and token, and how long a code stays
 * valid. Managers can view (never the token); admins change. The token is stored encrypted and
 * only its last characters are ever shown.
 */
@RestController
@RequestMapping("/settings/khqr")
public class KhqrSettingsController {

    /**
     * @param tokenSource    SAVED in the app, SERVER (BAKONG_TOKEN), or NONE
     * @param tokenHint      the token's last 4 characters, to recognise it
     * @param tokenExpiresAt when the token stops working, read from the token itself (Bakong tokens last 90 days)
     */
    public record KhqrSettingsDto(KhqrMode mode, boolean modeSaved, String accountId, String merchantName, String merchantCity,
                                  String apiBaseUrl, boolean apiBaseUrlSaved, int expiryMinutes, boolean expirySaved,
                                  KhqrConfig.TokenSource tokenSource, String tokenHint, Instant tokenExpiresAt,
                                  Instant tokenUpdatedAt, String updatedBy, Instant updatedAt) {
    }

    /**
     * @param apiToken    a new token; null or blank keeps the current one
     * @param removeToken deletes the saved token (the server's BAKONG_TOKEN, if any, applies again)
     */
    public record KhqrSettingsRequest(
            @NotNull KhqrMode mode,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[^@\\s]+@[^@\\s]+$", message = "must look like name@bank") String accountId,
            @NotBlank @Size(max = 25) String merchantName,
            @NotBlank @Size(max = 15) String merchantCity,
            @NotBlank @Size(max = 255) String apiBaseUrl,
            @Min(1) @Max(60) int expiryMinutes,
            @Size(max = 4000) String apiToken,
            boolean removeToken) {
    }

    /** Test the token being typed, or the saved one when {@code apiToken} is blank. */
    public record TestRequest(@Size(max = 255) String apiBaseUrl, @Size(max = 4000) String apiToken) {
    }

    private final CompanySettingsRepository companies;
    private final SettingsService settings;
    private final SecretBox secrets;
    private final BakongClient bakong;
    private final UserRepository users;
    private final AuditService audit;

    public KhqrSettingsController(CompanySettingsRepository companies, SettingsService settings, SecretBox secrets,
                                  BakongClient bakong, UserRepository users, AuditService audit) {
        this.companies = companies;
        this.settings = settings;
        this.secrets = secrets;
        this.bakong = bakong;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SETTINGS_VIEW')")
    public KhqrSettingsDto get() {
        settings.current();
        return toDto(companies.findById(CompanySettings.ID).orElseThrow());
    }

    @PutMapping
    @Transactional
    @PreAuthorize("hasAuthority('SETTINGS_KHQR')")
    public KhqrSettingsDto update(@Valid @RequestBody KhqrSettingsRequest req, @AuthenticationPrincipal AuthUser user) {
        settings.current();
        CompanySettings s = companies.findById(CompanySettings.ID).orElseThrow();
        KhqrConfig before = settings.khqr();
        String url = normalizeUrl(req.apiBaseUrl());
        String newToken = req.apiToken() == null || req.apiToken().isBlank() ? null : cleanToken(req.apiToken());

        boolean tokenAfter = newToken != null
                || (!req.removeToken() && before.tokenSource() == KhqrConfig.TokenSource.SAVED)
                || before.tokenSource() == KhqrConfig.TokenSource.SERVER;
        if (req.mode() == KhqrMode.BAKONG && !tokenAfter) {
            throw ApiException.badRequest("Add the Bakong API token before switching to live payments");
        }

        Changes changes = new Changes()
                .add("KHQR mode", label(before.mode()), label(req.mode()))
                .add("KHQR account", s.getKhqrAccountId(), req.accountId().trim())
                .add("KHQR merchant name", s.getKhqrMerchantName(), req.merchantName().trim())
                .add("KHQR city", s.getKhqrMerchantCity(), req.merchantCity().trim())
                .add("Bakong API address", before.apiBaseUrl(), url)
                .add("KHQR code valid for", before.expiry().toMinutes() + " min", req.expiryMinutes() + " min");
        if (newToken != null) {
            changes.add("Bakong API token", before.hasToken() ? "…" + hint(before.apiToken()) : "none", "…" + hint(newToken));
        } else if (req.removeToken() && before.tokenSource() == KhqrConfig.TokenSource.SAVED) {
            changes.add("Bakong API token", "…" + hint(before.apiToken()), "removed");
        }

        s.setKhqrMode(req.mode());
        s.setKhqrAccountId(req.accountId().trim());
        s.setKhqrMerchantName(req.merchantName().trim());
        s.setKhqrMerchantCity(req.merchantCity().trim());
        s.setKhqrApiBaseUrl(url);
        s.setKhqrExpiryMinutes(req.expiryMinutes());
        if (newToken != null) {
            s.setKhqrApiToken(secrets.encrypt(newToken));
            s.setKhqrTokenUpdatedAt(Instant.now());
        } else if (req.removeToken()) {
            s.setKhqrApiToken(null);
            s.setKhqrTokenUpdatedAt(Instant.now());
        }
        s.setUpdatedBy(users.getReferenceById(user.id()));
        s.setUpdatedAt(Instant.now());
        settings.refresh(companies.saveAndFlush(s));
        if (!changes.isEmpty()) {
            audit.log(AuditAction.SETTINGS_UPDATED, "Settings", "khqr", "KHQR settings: " + changes.describe(), changes);
        }
        return toDto(s);
    }

    /** Calls Bakong once with the given (or saved) address and token. Sends no payment. */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('SETTINGS_KHQR')")
    public BakongClient.CheckResult test(@Valid @RequestBody TestRequest req) {
        KhqrConfig cfg = settings.khqr();
        String url = req.apiBaseUrl() == null || req.apiBaseUrl().isBlank() ? cfg.apiBaseUrl() : normalizeUrl(req.apiBaseUrl());
        String token = req.apiToken() == null || req.apiToken().isBlank() ? cfg.apiToken() : cleanToken(req.apiToken());
        if (token == null || token.isBlank()) {
            throw ApiException.badRequest("Enter a Bakong API token to test");
        }
        return bakong.check(url, token);
    }

    private KhqrSettingsDto toDto(CompanySettings s) {
        KhqrConfig c = settings.khqr();
        return new KhqrSettingsDto(c.mode(), c.modeSaved(), c.accountId(), c.merchantName(), c.merchantCity(), c.apiBaseUrl(),
                c.apiBaseUrlSaved(), (int) c.expiry().toMinutes(), c.expirySaved(), c.tokenSource(),
                c.hasToken() ? hint(c.apiToken()) : null, c.hasToken() ? tokenExpiry(c.apiToken()) : null,
                s.getKhqrTokenUpdatedAt(), s.getUpdatedBy() == null ? null : s.getUpdatedBy().getFullName(), s.getUpdatedAt());
    }

    /** Only https addresses (http just for a local test server). Trailing slashes are dropped. */
    static String normalizeUrl(String raw) {
        String url = raw.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
            if (host == null || !("https".equals(scheme) || ("http".equals(scheme) && local))
                    || uri.getQuery() != null || uri.getUserInfo() != null) {
                throw ApiException.badRequest("The Bakong API address must be an https:// address, e.g. https://api-bakong.nbc.gov.kh");
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The Bakong API address is not a valid address");
        }
        return url;
    }

    static String cleanToken(String raw) {
        String t = raw.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        if (t.length() < 20 || t.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.badRequest("That doesn't look like a Bakong API token (paste the whole token, without spaces)");
        }
        return t;
    }

    static String hint(String token) {
        return token.length() <= 4 ? "****" : token.substring(token.length() - 4);
    }

    /** The "exp" claim of a JWT-shaped token, without checking its signature; null if it has none. */
    static Instant tokenExpiry(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            var exp = JsonMapper.builder().build().readTree(json).get("exp");
            return exp != null && exp.isNumber() ? Instant.ofEpochSecond(exp.asLong()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String label(KhqrMode mode) {
        return mode == KhqrMode.SIMULATED ? "test (simulated)" : "live (Bakong)";
    }
}
