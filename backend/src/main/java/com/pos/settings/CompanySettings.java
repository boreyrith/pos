package com.pos.settings;

import com.pos.config.AppProperties;
import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.auth.User;
import com.pos.tax.TaxMode;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The single row of company information and system-wide settings. */
@Entity
@Table(name = "company_settings")
@Getter
@Setter
@NoArgsConstructor
public class CompanySettings {

    public static final short ID = 1;

    @Id
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Short id = ID;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "legal_name", length = 150)
    private String legalName;

    /** VAT TIN or other tax registration number, printed on receipts. */
    @Column(name = "tax_id", length = 50)
    private String taxId;

    @Column(length = 255)
    private String address;

    @Column(length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 150)
    private String website;

    @Column(name = "receipt_footer", length = 500)
    private String receiptFooter;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_mode", nullable = false, length = 12)
    private TaxMode taxMode = TaxMode.EXCLUSIVE;

    /** What VAT is called on screens and receipts. */
    @Column(name = "tax_label", nullable = false, length = 30)
    private String taxLabel = "VAT";

    /** The highest tax rate that may be set (a fraction). */
    @Column(name = "tax_max_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal taxMaxRate = new BigDecimal("0.30");

    @Column(name = "tax_products", nullable = false)
    private boolean taxProducts = true;

    @Column(name = "tax_services", nullable = false)
    private boolean taxServices = true;

    @Column(name = "tax_items", nullable = false)
    private boolean taxItems = true;

    @Column(name = "tax_packages", nullable = false)
    private boolean taxPackages = true;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(name = "khqr_account_id", nullable = false, length = 100)
    private String khqrAccountId;

    @Column(name = "khqr_merchant_name", nullable = false, length = 25)
    private String khqrMerchantName;

    @Column(name = "khqr_merchant_city", nullable = false, length = 15)
    private String khqrMerchantCity;

    /** Null fields below follow the server defaults (app.khqr.*). */
    @Enumerated(EnumType.STRING)
    @Column(name = "khqr_mode", length = 10)
    private AppProperties.KhqrMode khqrMode;

    @Column(name = "khqr_api_base_url", length = 255)
    private String khqrApiBaseUrl;

    /** Encrypted with {@link com.pos.common.SecretBox}. */
    @Column(name = "khqr_api_token")
    private byte[] khqrApiToken;

    @Column(name = "khqr_token_updated_at")
    private Instant khqrTokenUpdatedAt;

    @Column(name = "khqr_expiry_minutes")
    private Integer khqrExpiryMinutes;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "MEDIUMBLOB")
    private byte[] logo;

    @Column(name = "logo_type", length = 30)
    private String logoType;

    /** Bumped on each logo change so browsers fetch the new one. */
    @Column(name = "logo_version", nullable = false)
    private int logoVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
