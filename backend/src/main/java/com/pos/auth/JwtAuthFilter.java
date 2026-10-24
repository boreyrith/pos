package com.pos.auth;

import com.pos.access.AccessService;
import com.pos.access.Permission;
import com.pos.access.RoleAccess;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** What someone who must change their password can still reach. */
    private static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE =
            Set.of("/auth/me", "/auth/password", "/auth/logout", "/auth/refresh", "/store");

    private final JwtService jwtService;
    private final AccessService access;

    public JwtAuthFilter(JwtService jwtService, AccessService access) {
        this.jwtService = jwtService;
        this.access = access;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.TokenIdentity id = jwtService.parse(header.substring(7));
                // A disabled or deleted account stops working at once, not when the token expires.
                var current = access.user(id.userId()).filter(AccessService.UserAccess::active).orElse(null);
                if (current == null) {
                    chain.doFilter(request, response);
                    return;
                }
                RoleAccess role = access.role(current.roleId());
                AuthUser user = new AuthUser(id.userId(), id.username(), id.fullName(), role, current.branchId(),
                        id.mustChangePassword());
                if (user.mustChangePassword() && !ALLOWED_BEFORE_PASSWORD_CHANGE.contains(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/problem+json");
                    response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"code\":\"PASSWORD_CHANGE_REQUIRED\","
                            + "\"detail\":\"Choose a new password to continue\"}");
                    return;
                }
                // Each permission is an authority: endpoints check them with hasAuthority('...').
                List<SimpleGrantedAuthority> authorities = role.permissions().stream()
                        .map(p -> new SimpleGrantedAuthority(p.name())).toList();
                if (role.superAdmin()) {
                    authorities = java.util.stream.Stream.concat(
                            java.util.Arrays.stream(Permission.values()).map(p -> new SimpleGrantedAuthority(p.name())),
                            java.util.stream.Stream.of(new SimpleGrantedAuthority("SUPER_ADMIN"))).toList();
                }
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ignored) {
                // Invalid or expired token: continue unauthenticated, the entry point answers 401.
            }
        }
        chain.doFilter(request, response);
    }
}
