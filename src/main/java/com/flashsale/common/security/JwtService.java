package com.flashsale.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates short-lived JWT access tokens.
 * Refresh tokens are opaque random strings stored (hashed) in the DB so they
 * can be revoked on logout — a pure-JWT refresh token cannot be revoked.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long accessTokenTtlMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public String generateAccessToken(Long userId, String identifier) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("identifier", identifier)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        return Long.valueOf(parseAndValidate(token).getSubject());
    }
}
