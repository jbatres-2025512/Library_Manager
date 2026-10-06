package org.jbatres.library_manager_api.security;

import org.jbatres.library_manager_api.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and validates signed JWTs (HS256).
 * Claims: sub = email, uid = user id, role = role name, iat, exp.
 * The secret is used as raw UTF-8 bytes and must be at least 32 bytes long; the application
 * refuses to start otherwise, so a weak or missing secret can never go unnoticed.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_ROLE = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final JwtParser parser;
    private final long expirationMs;
    private final Clock clock;

    public JwtService(@Value("${app.jwt.secret:}") String secret,
                      @Value("${app.jwt.expiration-ms:3600000}") long expirationMs,
                      Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set and contain at least " + MIN_SECRET_BYTES + " characters");
        }
        if (expirationMs <= 0) {
            throw new IllegalStateException("JWT_EXPIRATION_MS must be greater than zero");
        }
        if (secret.startsWith("change_me")) {
            log.warn("JWT_SECRET still has the placeholder value from .env.example; use a random secret");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public String generateToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(CLAIM_UID, user.getId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies signature and expiration and rebuilds the principal from the claims.
     *
     * @throws JwtException if the token is malformed, unsigned, tampered, expired or lacks required claims
     *                      (io.jsonwebtoken.ExpiredJwtException is the subtype used for expiration)
     */
    public AuthenticatedUser parseToken(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();

        String email = claims.getSubject();
        Object uid = claims.get(CLAIM_UID);
        String roleName = claims.get(CLAIM_ROLE, String.class);
        if (email == null || !(uid instanceof Number) || roleName == null) {
            throw new JwtException("Token is missing required claims");
        }
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Token contains an unknown role");
        }
        return new AuthenticatedUser(((Number) uid).longValue(), email, role, null);
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }
}