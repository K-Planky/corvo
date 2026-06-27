package dev.kplanky.othello.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing configuration (spec §10). Bound from the {@code jwt.*} properties.
 *
 * <p>The {@code secret} is sourced from the environment (see {@code application.properties}); M3.3
 * removes any in-source default so no secret literal ships in the build. HS256 requires a key of at
 * least 256 bits, so the secret must be at least 32 bytes.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {

    public JwtProperties {
        if (accessTokenTtl == null) {
            // 24 h access token, no refresh in Core (spec §10 / Appendix C A6).
            accessTokenTtl = Duration.ofHours(24);
        }
    }
}
