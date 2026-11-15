package com.pos.mobileapp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthUser;
import com.pos.auth.UserRepository;
import com.pos.common.ApiException;
import com.pos.common.Hashes;

import jakarta.validation.constraints.Size;

/**
 * The mobile app's download page (frontend-app) and its settings (Settings > Mobile app): version,
 * what's new, the Android install file (APK) and store links. The APK is kept in the database and
 * held in memory once read, so many downloads don't each read the database.
 */
@Service
public class MobileAppService {

    /** Up to 100 MB (a Flutter APK is usually 20 to 60 MB). The proxies allow this size on the upload address only. */
    public static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    /** What the app needs (mobile/: Flutter's minSdk 24, IPHONEOS_DEPLOYMENT_TARGET 15.0). */
    static final String ANDROID_MINIMUM = "7.0";
    static final String IOS_MINIMUM = "15.0";

    public record ApkDto(String fileName, long sizeBytes, String sha256, Instant uploadedAt) {
    }

    /** For everyone (the download page). */
    public record PublicDto(String versionName, String releaseNotes, Instant updatedAt, ApkDto apk, String playStoreUrl,
                            String appStoreUrl, String testflightUrl, String androidMinimum, String iosMinimum) {
    }

    /** For staff (Settings > Mobile app): the same, plus downloads and who last changed it. */
    public record SettingsDto(PublicDto app, int apkDownloads, String updatedBy) {
    }

    public record SettingsRequest(@Size(max = 30) String versionName, @Size(max = 2000) String releaseNotes,
                                  @Size(max = 255) String playStoreUrl, @Size(max = 255) String appStoreUrl,
                                  @Size(max = 255) String testflightUrl) {
    }

    /** The file to send: its name and bytes. */
    public record ApkFile(String fileName, byte[] data, String sha256) {
    }

    private final MobileAppRepository apps;
    private final MobileAppApkRepository apks;
    private final UserRepository users;
    private final AuditService audit;
    /** The APK last read, by its checksum: a new upload changes the checksum. */
    private volatile ApkFile cached;

    public MobileAppService(MobileAppRepository apps, MobileAppApkRepository apks, UserRepository users, AuditService audit) {
        this.apps = apps;
        this.apks = apks;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PublicDto get() {
        return toPublic(load());
    }

    @Transactional(readOnly = true)
    public SettingsDto settings() {
        MobileApp m = load();
        return new SettingsDto(toPublic(m), m.getApkDownloads(), m.getUpdatedBy() == null ? null : m.getUpdatedBy().getFullName());
    }

    @Transactional
    public SettingsDto update(SettingsRequest req, AuthUser user) {
        MobileApp m = load();
        String play = link(req.playStoreUrl(), "https://play.google.com/", "Google Play");
        String store = link(req.appStoreUrl(), "https://apps.apple.com/", "App Store");
        String testflight = link(req.testflightUrl(), "https://testflight.apple.com/", "TestFlight");
        Changes changes = new Changes()
                .add("Version", m.getVersionName(), blankToNull(req.versionName()))
                .add("What's new", m.getReleaseNotes(), blankToNull(req.releaseNotes()))
                .add("Google Play", m.getPlayStoreUrl(), play)
                .add("App Store", m.getAppStoreUrl(), store)
                .add("TestFlight", m.getTestflightUrl(), testflight);
        m.setVersionName(blankToNull(req.versionName()));
        m.setReleaseNotes(blankToNull(req.releaseNotes()));
        m.setPlayStoreUrl(play);
        m.setAppStoreUrl(store);
        m.setTestflightUrl(testflight);
        touch(m, user);
        if (!changes.isEmpty()) {
            audit.log(AuditAction.MOBILE_APP_UPDATED, "Settings", "mobile-app", "Mobile app: " + changes.describe(), changes);
        }
        return settings();
    }

    /** A new Android install file. Refused unless it really is an Android package (a zip with a manifest and code). */
    @Transactional
    public SettingsDto uploadApk(byte[] bytes, AuthUser user) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("Choose the APK file to upload");
        }
        if (bytes.length > MAX_APK_BYTES) {
            throw ApiException.badRequest("The APK must be 100 MB or smaller");
        }
        if (!isApk(bytes)) {
            throw ApiException.badRequest("That file is not an Android app (APK)");
        }
        MobileApp m = load();
        String sha = Hashes.sha256(bytes);
        MobileAppApk file = apks.findById(MobileApp.ID).orElseGet(MobileAppApk::new);
        file.setData(bytes);
        apks.save(file);
        String version = m.getVersionName() == null ? "latest" : m.getVersionName().replaceAll("[^A-Za-z0-9._-]", "");
        m.setApkFileName("POS-" + (version.isEmpty() ? "latest" : version) + ".apk");
        m.setApkSize((long) bytes.length);
        m.setApkSha256(sha);
        m.setApkUploadedAt(Instant.now());
        m.setApkDownloads(0);
        touch(m, user);
        cached = null;
        audit.log(AuditAction.MOBILE_APP_UPDATED, "Settings", "mobile-app",
                "Uploaded the Android app " + m.getApkFileName() + " (" + (bytes.length / 1024 / 1024) + " MB, SHA-256 " + sha + ")");
        return settings();
    }

    @Transactional
    public SettingsDto removeApk(AuthUser user) {
        MobileApp m = load();
        if (m.getApkFileName() != null) {
            apks.deleteById(MobileApp.ID);
            audit.log(AuditAction.MOBILE_APP_UPDATED, "Settings", "mobile-app", "Removed the Android app " + m.getApkFileName());
        }
        m.setApkFileName(null);
        m.setApkSize(null);
        m.setApkSha256(null);
        m.setApkUploadedAt(null);
        m.setApkDownloads(0);
        touch(m, user);
        cached = null;
        return settings();
    }

    /** The APK for download (counted). Not found when none has been uploaded. */
    @Transactional
    public ApkFile download() {
        MobileApp m = load();
        if (m.getApkSha256() == null) {
            throw ApiException.notFound("Android app");
        }
        ApkFile file = cached;
        if (file == null || !file.sha256().equals(m.getApkSha256())) {
            byte[] data = apks.findById(MobileApp.ID).map(MobileAppApk::getData).orElseThrow(() -> ApiException.notFound("Android app"));
            file = new ApkFile(m.getApkFileName(), data, m.getApkSha256());
            cached = file;
        }
        apps.countDownload();
        return file;
    }

    private MobileApp load() {
        return apps.findById(MobileApp.ID).orElseThrow();
    }

    private void touch(MobileApp m, AuthUser user) {
        m.setUpdatedBy(users.getReferenceById(user.id()));
        m.setUpdatedAt(Instant.now());
    }

    private static PublicDto toPublic(MobileApp m) {
        ApkDto apk = m.getApkSha256() == null ? null
                : new ApkDto(m.getApkFileName(), m.getApkSize(), m.getApkSha256(), m.getApkUploadedAt());
        return new PublicDto(m.getVersionName(), m.getReleaseNotes(), m.getUpdatedAt(), apk, m.getPlayStoreUrl(), m.getAppStoreUrl(),
                m.getTestflightUrl(), ANDROID_MINIMUM, IOS_MINIMUM);
    }

    /** Store links only to the store itself (https, its own address), so the page can't send people elsewhere. */
    private static String link(String raw, String prefix, String store) {
        String s = blankToNull(raw);
        if (s != null && (!s.startsWith(prefix) || s.contains(" ") || s.contains("\\"))) {
            throw ApiException.badRequest("The " + store + " link must start with " + prefix);
        }
        return s;
    }

    /** A zip whose entries include AndroidManifest.xml and compiled code (classes.dex). */
    static boolean isApk(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            return false;
        }
        boolean manifest = false;
        boolean code = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null && !(manifest && code); ) {
                manifest |= e.getName().equals("AndroidManifest.xml");
                code |= e.getName().equals("classes.dex");
            }
        } catch (IOException e) {
            return false;
        }
        return manifest && code;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
