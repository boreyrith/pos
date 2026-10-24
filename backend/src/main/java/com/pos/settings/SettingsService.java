package com.pos.settings;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.common.SecretBox;
import com.pos.config.AppProperties;

/**
 * Company settings, read on every sale and report, so they are cached in memory and refreshed
 * whenever they change. The first start fills the row from the environment defaults.
 */
@Service
public class SettingsService {

    private final CompanySettingsRepository repository;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private volatile Company cached;
    private volatile KhqrConfig khqr;
    private final SecretBox secrets;

    public SettingsService(CompanySettingsRepository repository, AppProperties props, PlatformTransactionManager txManager,
                           SecretBox secrets) {
        this.repository = repository;
        this.secrets = secrets;
        this.props = props;
        // Its own transaction: the row created on first use must not roll back with a caller
        // that fails afterwards, or the cache would describe a row that doesn't exist.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Company current() {
        Company c = cached;
        if (c == null) {
            synchronized (this) {
                if (cached == null) {
                    tx.executeWithoutResult(s -> load(repository.findById(CompanySettings.ID).orElseGet(this::create)));
                }
                c = cached;
            }
        }
        return c;
    }

    /** The KHQR / Bakong settings in effect, with the token decrypted. */
    public KhqrConfig khqr() {
        current();
        return khqr;
    }

    /** Call after changing the row, inside or after the changing transaction. */
    public void refresh(CompanySettings saved) {
        load(saved);
    }

    private void load(CompanySettings s) {
        var d = props.khqr();
        String saved = secrets.decrypt(s.getKhqrApiToken());
        boolean serverToken = d.apiToken() != null && !d.apiToken().isBlank();
        khqr = new KhqrConfig(
                s.getKhqrMode() != null ? s.getKhqrMode() : d.mode(),
                s.getKhqrAccountId(), s.getKhqrMerchantName(), s.getKhqrMerchantCity(),
                s.getKhqrApiBaseUrl() != null ? s.getKhqrApiBaseUrl() : d.apiBaseUrl(),
                saved != null ? saved : serverToken ? d.apiToken() : null,
                s.getKhqrExpiryMinutes() != null ? Duration.ofMinutes(s.getKhqrExpiryMinutes()) : d.expiry(),
                s.getKhqrMode() != null, s.getKhqrApiBaseUrl() != null,
                saved != null ? KhqrConfig.TokenSource.SAVED : serverToken ? KhqrConfig.TokenSource.SERVER : KhqrConfig.TokenSource.NONE,
                s.getKhqrExpiryMinutes() != null);
        cached = Company.from(s);
    }

    private CompanySettings create() {
        var store = props.store();
        var khqr = props.khqr();
        CompanySettings s = new CompanySettings();
        s.setName(store.name());
        s.setTimezone(store.timezone().getId());
        s.setKhqrAccountId(khqr.bakongAccountId());
        s.setKhqrMerchantName(truncate(khqr.merchantName(), 25));
        s.setKhqrMerchantCity(truncate(khqr.merchantCity(), 15));
        s.setReceiptFooter("Thank you! · អរគុណ!");
        return repository.saveAndFlush(s);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
