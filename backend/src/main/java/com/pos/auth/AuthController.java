package com.pos.auth;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.AuthDtos.ChangePasswordRequest;
import com.pos.auth.AuthDtos.LanguageRequest;
import com.pos.auth.AuthDtos.LoginRequest;
import com.pos.auth.AuthDtos.TokenResponse;
import com.pos.auth.AuthDtos.UserDto;
import com.pos.common.ApiException;
import com.pos.config.AppProperties;

import jakarta.validation.Valid;

/**
 * The access token is returned in the body (kept in memory by the client); the refresh token
 * travels only in an HttpOnly cookie scoped to the auth endpoints.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "pos_refresh";

    private final AuthService authService;
    private final UserRepository users;
    private final AppProperties props;

    public AuthController(AuthService authService, UserRepository users, AppProperties props) {
        this.authService = authService;
        this.users = users;
        this.props = props;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return withCookie(authService.login(req.username(), req.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        return withCookie(authService.refresh(token));
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
                                                        @AuthenticationPrincipal AuthUser principal) {
        return withCookie(authService.changePassword(principal.id(), req.currentPassword(), req.newPassword()));
    }

    /** Sets your own interface language; it follows you to every device. */
    @PutMapping("/me/language")
    @Transactional
    public UserDto language(@Valid @RequestBody LanguageRequest req, @AuthenticationPrincipal AuthUser principal) {
        User user = users.findWithBranchById(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        user.setLanguage(req.language());
        return UserDto.from(user);
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser principal) {
        return users.findWithBranchById(principal.id()).map(UserDto::from)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private ResponseEntity<TokenResponse> withCookie(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookie(session.refreshToken(), props.jwt().refreshTokenTtl()).toString())
                .body(session.tokens());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.jwt().refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }
}
