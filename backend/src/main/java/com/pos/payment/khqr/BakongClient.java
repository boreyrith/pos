package com.pos.payment.khqr;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.pos.settings.KhqrConfig;
import com.pos.settings.SettingsService;

/**
 * Looks up KHQR payments on the Bakong Open API by the QR string's MD5. The API address and token
 * come from Settings > KHQR payments (or the server defaults) on every call, so changes apply at once.
 */
@Component
public class BakongClient {

    /** A transaction Bakong reports for an MD5. */
    public record Transaction(String hash, String toAccountId, BigDecimal amount, String currency) {
    }

    /** The outcome of a connection test. */
    public enum CheckStatus { OK, UNAUTHORIZED, UNREACHABLE, ERROR }

    public record CheckResult(CheckStatus status, String message) {
    }

    record CheckResponse(Integer responseCode, String responseMessage, Integer errorCode, Map<String, Object> data) {
    }

    private static final Logger log = LoggerFactory.getLogger(BakongClient.class);

    /** An MD5 no real payment has, for testing the connection without touching any transaction. */
    private static final String PROBE_MD5 = "00000000000000000000000000000000";

    private final SettingsService settings;
    private final SimpleClientHttpRequestFactory requestFactory;

    public BakongClient(SettingsService settings) {
        this.settings = settings;
        this.requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
    }

    private RestClient client(String baseUrl, String token) {
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    private CheckResponse lookup(String baseUrl, String token, String md5) {
        return client(baseUrl, token).post().uri("/v1/check_transaction_by_md5")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("md5", md5))
                .retrieve()
                .body(CheckResponse.class);
    }

    /** Empty when Bakong has no transaction for the MD5 yet, or when the lookup fails. */
    public Optional<Transaction> findByMd5(String md5) {
        KhqrConfig cfg = settings.khqr();
        if (!cfg.hasToken()) {
            log.warn("No Bakong API token is set; can't check KHQR payment {}", md5);
            return Optional.empty();
        }
        try {
            CheckResponse res = lookup(cfg.apiBaseUrl(), cfg.apiToken(), md5);
            if (res == null || res.responseCode() == null || res.responseCode() != 0 || res.data() == null) {
                return Optional.empty();
            }
            Map<String, Object> d = res.data();
            return Optional.of(new Transaction(
                    String.valueOf(d.get("hash")),
                    String.valueOf(d.get("toAccountId")),
                    new BigDecimal(String.valueOf(d.get("amount"))),
                    String.valueOf(d.get("currency"))));
        } catch (RestClientException | NumberFormatException e) {
            log.warn("Bakong lookup failed for md5 {}: {}", md5, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks that {@code baseUrl} answers and accepts {@code token}, by asking for a transaction that
     * doesn't exist: "not found" means the token works.
     */
    public CheckResult check(String baseUrl, String token) {
        try {
            CheckResponse res = lookup(baseUrl, token, PROBE_MD5);
            if (res == null || res.responseCode() == null) {
                return new CheckResult(CheckStatus.ERROR, "Bakong sent an unexpected answer");
            }
            return new CheckResult(CheckStatus.OK, "Connected to Bakong; the token was accepted");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                return new CheckResult(CheckStatus.UNAUTHORIZED, "Bakong refused the token (expired or invalid). Renew it on the Bakong API portal.");
            }
            return new CheckResult(CheckStatus.ERROR, "Bakong answered with HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.info("Bakong connection test failed: {}", e.getMessage());
            return new CheckResult(CheckStatus.UNREACHABLE, "Could not reach " + baseUrl + " (check the address and the server's internet access)");
        }
    }
}
