package com.pos.auth;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.pos.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;
    private final AppProperties.Jwt props;

    public JwtService(AppProperties appProperties) {
        this.props = appProperties.jwt();
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secret()));
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("name", user.getFullName())
                .claim("pwc", user.isMustChangePassword())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    /** Who a valid token belongs to. Role and branch are not in the token: they are looked up per request. */
    public record TokenIdentity(Long userId, String username, String fullName, boolean mustChangePassword) {
    }

    /** Returns the identity in a valid token, or throws {@link JwtException}. */
    public TokenIdentity parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new TokenIdentity(
                Long.valueOf(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("name", String.class),
                Boolean.TRUE.equals(claims.get("pwc", Boolean.class)));
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }
}
