package com.pos.mobileapp;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.auth.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The mobile app's download page (one row): version, what's new, store links and the APK's details. See V41. */
@Entity
@Table(name = "mobile_app")
@Getter
@Setter
@NoArgsConstructor
public class MobileApp {

    public static final short ID = 1;

    @Id
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Short id = ID;

    @Column(name = "version_name", length = 30)
    private String versionName;

    @Column(name = "release_notes", length = 2000)
    private String releaseNotes;

    @Column(name = "play_store_url", length = 255)
    private String playStoreUrl;

    @Column(name = "app_store_url", length = 255)
    private String appStoreUrl;

    @Column(name = "testflight_url", length = 255)
    private String testflightUrl;

    /** The uploaded APK's name for download, size in bytes and SHA-256 (all null when there is none). */
    @Column(name = "apk_file_name", length = 120)
    private String apkFileName;

    @Column(name = "apk_size")
    private Long apkSize;

    @Column(name = "apk_sha256", length = 64)
    private String apkSha256;

    @Column(name = "apk_uploaded_at")
    private Instant apkUploadedAt;

    @Column(name = "apk_downloads", nullable = false)
    private int apkDownloads;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
