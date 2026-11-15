package com.pos.mobileapp;

import java.io.IOException;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pos.auth.AuthUser;
import com.pos.mobileapp.MobileAppService.PublicDto;
import com.pos.mobileapp.MobileAppService.SettingsDto;
import com.pos.mobileapp.MobileAppService.SettingsRequest;

import jakarta.validation.Valid;

/**
 * The mobile app's download page: /mobile-app and /mobile-app/apk are open to everyone (SecurityConfig);
 * /settings/mobile-app is Settings > Mobile app (view with SETTINGS_VIEW, change with SETTINGS_COMPANY).
 */
@RestController
public class MobileAppController {

    private final MobileAppService service;

    public MobileAppController(MobileAppService service) {
        this.service = service;
    }

    @GetMapping("/mobile-app")
    public PublicDto get() {
        return service.get();
    }

    /** The Android install file. Its checksum is on the download page, to check the file arrived whole. */
    @GetMapping("/mobile-app/apk")
    public ResponseEntity<byte[]> apk() {
        var file = service.download();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .eTag(file.sha256())
                .body(file.data());
    }

    @GetMapping("/settings/mobile-app")
    @PreAuthorize("hasAuthority('SETTINGS_VIEW')")
    public SettingsDto settings() {
        return service.settings();
    }

    @PutMapping("/settings/mobile-app")
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public SettingsDto update(@Valid @RequestBody SettingsRequest req, @AuthenticationPrincipal AuthUser user) {
        return service.update(req, user);
    }

    @PutMapping(value = "/settings/mobile-app/apk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public SettingsDto uploadApk(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthUser user) throws IOException {
        return service.uploadApk(file.getBytes(), user);
    }

    @DeleteMapping("/settings/mobile-app/apk")
    @PreAuthorize("hasAuthority('SETTINGS_COMPANY')")
    public SettingsDto removeApk(@AuthenticationPrincipal AuthUser user) {
        return service.removeApk(user);
    }
}
