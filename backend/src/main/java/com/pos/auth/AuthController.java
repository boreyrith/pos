package com.pos.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pos.auth.AuthDtos.AvatarRequest;
import com.pos.auth.AuthDtos.ChangePasswordRequest;
import com.pos.auth.AuthDtos.LanguageRequest;
import com.pos.auth.AuthDtos.LoginRequest;
import com.pos.auth.AuthDtos.TokenResponse;
import com.pos.auth.AuthDtos.UserDto;
import com.pos.common.ApiException;
import com.pos.common.AppProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * The access token is returned in the body (kept in memory by the client); the refresh token
 * travels only in an HttpOnly cookie scoped to the auth endpoints.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SessionService sessions;
    private final ProfilePhotoService photos;
    private final MyDetailsService myDetails;
    private final UserSessionRepository userSessions;

    static final String REFRESH_COOKIE = "pos_refresh";

    private final AuthService authService;
    private final UserRepository users;
    private final AppProperties props;

    public AuthController(AuthService authService, UserRepository users, AppProperties props, SessionService sessions,
                          ProfilePhotoService photos, MyDetailsService myDetails, UserSessionRepository userSessions) {
        this.sessions = sessions;
        this.userSessions = userSessions;
        this.myDetails = myDetails;
        this.photos = photos;
        this.authService = authService;
        this.users = users;
        this.props = props;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        return withCookie(authService.login(req.username(), req.password(), req.remember() == null || req.remember(),
                ClientInfo.from(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                                 HttpServletRequest request) {
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        return withCookie(authService.refresh(token, ClientInfo.from(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token != null) {
            authService.logout(token);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    /** Change your own password. Signs out your other sessions and renews this one. */
    @PostMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                        @AuthenticationPrincipal AuthUser principal, HttpServletRequest request) {
        // A new session after a password change keeps this one's "Remember me".
        boolean remember = principal.sessionId() == null
                || userSessions.findById(principal.sessionId()).map(UserSession::isRemember).orElse(true);
        return withCookie(authService.changePassword(principal.id(), req.currentPassword(), req.newPassword(), remember,
                ClientInfo.from(request)));
    }

    /** Your signed-in devices; the one you are using is marked current. */
    @GetMapping("/sessions")
    public List<SessionService.SessionDto> sessions(@AuthenticationPrincipal AuthUser principal) {
        return sessions.mine(principal);
    }

    /** Signs one of your other devices out. */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> endSession(@PathVariable String id, @AuthenticationPrincipal AuthUser principal) {
        sessions.endMine(id, principal);
        return ResponseEntity.noContent().build();
    }

    /** Signs out every device but this one. */
    @PostMapping("/sessions/end-others")
    public java.util.Map<String, Integer> endOtherSessions(@AuthenticationPrincipal AuthUser principal) {
        return java.util.Map.of("ended", sessions.endMyOthers(principal));
    }

    /** Sets your own interface language; it follows you to every device. */
    @PutMapping("/me/language")
    @Transactional
    public UserDto language(@Valid @RequestBody LanguageRequest req, @AuthenticationPrincipal AuthUser principal) {
        User user = users.findWithBranchById(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        user.setLanguage(req.language());
        return UserDto.from(user);
    }

    /** Adds or replaces your own photo (PNG, JPEG or WebP up to 2 MB). */
    @PutMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDto uploadPhoto(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthUser principal) throws IOException {
        return photos.set(principal.id(), file.getBytes());
    }

    /** Your own personal details (not the display name, role or branch: user admins set those). */
    @PutMapping("/me/details")
    public UserDto details(@Valid @RequestBody PersonalDetails req, @AuthenticationPrincipal AuthUser principal) {
        return myDetails.update(principal.id(), req);
    }

    /** Picks a built-in avatar instead of a photo, or none (null) for your initials. */
    @PutMapping("/me/avatar")
    public UserDto avatar(@Valid @RequestBody AvatarRequest req, @AuthenticationPrincipal AuthUser principal) {
        return photos.chooseAvatar(principal.id(), req.avatar());
    }

    @DeleteMapping("/me/photo")
    public UserDto removePhoto(@AuthenticationPrincipal AuthUser principal) {
        return photos.remove(principal.id());
    }

    /**
     * Your own photo, only with your sign-in (the app fetches it with its token, so people's photos
     * are never public). The app asks for it with ?v=photoVersion, so it can be cached privately.
     */
    @GetMapping("/me/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthUser principal) {
        UserPhoto photo = photos.get(principal.id()).orElseThrow(() -> ApiException.notFound("Photo"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.getData());
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser principal) {
        return users.findWithBranchById(principal.id()).map(UserDto::from)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private ResponseEntity<TokenResponse> withCookie(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        // Without "Remember me" the cookie has no lifetime, so it ends when the browser closes.
                        cookie(session.refreshToken(), session.remember() ? props.jwt().refreshTokenTtl() : null).toString())
                .body(session.tokens());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.jwt().refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge == null ? Duration.ofSeconds(-1) : maxAge)
                .build();
    }
}
