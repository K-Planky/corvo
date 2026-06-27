package dev.kplanky.othello.auth;

import dev.kplanky.othello.config.JwtProperties;
import dev.kplanky.othello.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues signed HS256 access tokens (spec §10). The token's subject is the user id; the username
 * rides as a claim for convenience.
 *
 * <p>M3.1 needs only issuance to return a token from registration. Verification and the
 * authentication filter that consumes these tokens are added in M3.2.
 */
@Service
public class JwtService {

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
}
