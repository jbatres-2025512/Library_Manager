package org.jbatres.library_manager_api.security;

import org.jbatres.library_manager_api.entity.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests: no Spring context and no database. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-with-at-least-32-bytes!!";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
    private final JwtService jwtService = new JwtService(SECRET, 3_600_000L, clock);
    private final AuthenticatedUser reader = new AuthenticatedUser(7L, "reader@library.com", Role.LECTOR, "hash");

    @Test
    void generateAndParse_roundTripKeepsIdentityAndRole() {
        AuthenticatedUser parsed = jwtService.parseToken(jwtService.generateToken(reader));

        assertThat(parsed.getId()).isEqualTo(7L);
        assertThat(parsed.getEmail()).isEqualTo("reader@library.com");
        assertThat(parsed.getRole()).isEqualTo(Role.LECTOR);
        assertThat(parsed.getPassword()).isNull();
        assertThat(parsed.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_LECTOR");
    }

    @Test
    void expiresInSeconds_isDerivedFromConfiguration() {
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(3600);
    }

    @Test
    void parse_rejectsExpiredToken_butAcceptsItJustBeforeExpiration() {
        String token = jwtService.generateToken(reader);

        clock.advance(Duration.ofMinutes(59));
        assertThat(jwtService.parseToken(token).getId()).isEqualTo(7L);

        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> jwtService.parseToken(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parse_rejectsTamperedPayload() {
        String[] parts = jwtService.generateToken(reader).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"LECTOR\"");

        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("LECTOR", "ADMIN").getBytes(StandardCharsets.UTF_8));
        String forgedToken = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parseToken(forgedToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_rejectsTokenSignedWithAnotherSecret() {
        JwtService other = new JwtService("another-secret-key-also-longer-than-32-bytes!", 3_600_000L, clock);
        String foreignToken = other.generateToken(reader);

        assertThatThrownBy(() -> jwtService.parseToken(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_rejectsUnsignedToken() {
        String unsigned = Jwts.builder()
                .subject("admin@library.com")
                .claim("uid", 1)
                .claim("role", "ADMIN")
                .compact();

        assertThatThrownBy(() -> jwtService.parseToken(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_rejectsSignedTokenWithoutRoleClaim() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("a@library.com")
                .claim("uid", 1)
                .expiration(Date.from(clock.instant().plusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseToken(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.parseToken("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void constructor_failsFastOnMissingOrWeakSecret() {
        assertThatThrownBy(() -> new JwtService("", 1000L, clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("too-short", 1000L, clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(SECRET, 0L, clock)).isInstanceOf(IllegalStateException.class);
    }

    /** Test clock that can be moved forward. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}