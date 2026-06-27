package dev.kplanky.othello.auth;

import dev.kplanky.othello.config.JwtProperties;
import dev.kplanky.othello.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies signed HS256 access tokens (spec §10). The token's subject is the user id; the
 * username rides as a claim for convenience.
 */
@Service
public class JwtService {

    /** Verified identity carried by a valid token. */
    public record JwtPrincipal(UUID userId, String username) {}

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        // HS256 requires a >=256-bit key; a short secret throws here, surfacing misconfiguration early.
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Mint a signed access token for the given user, expiring after the configured TTL. */
    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenTtl());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verify a token's signature and expiry and extract its identity. Throws {@link
     * io.jsonwebtoken.JwtException} (or {@link IllegalArgumentException} for a null/blank token) when
     * the token is missing, malformed, expired, or signed with the wrong key.
     */
    public JwtPrincipal parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new JwtPrincipal(UUID.fromString(claims.getSubject()), claims.get("username", String.class));
    }
}
