package dev.kplanky.othello.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * M3.3 acceptance (spec §10/§13): the genuine secrets, the JWT signing key and the DB password,
 * must be read from the environment with NO in-source default, so the committed build ships no
 * secret literal. (Semgrep's {@code p/secrets} ruleset enforces this more broadly in M5.)
 *
 * <p>Reads the shipped {@code application.properties} from the classpath (the same file packaged
 * into the build); {@code java.util.Properties} keeps {@code ${...}} placeholders verbatim (no
 * resolution at parse time), so an env placeholder is distinguishable from a hardcoded value.
 */
class NoCommittedSecretsTest {

    /** A bare env placeholder with no inline default: {@code ${NAME}}, never {@code ${NAME:default}}. */
    private static final Pattern BARE_ENV_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+}$");

    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertThat(in)
                    .as("application.properties should be packaged on the classpath")
                    .isNotNull();
            properties.load(in);
        }
        return properties;
    }

    @Test
    void jwtSigningKeyIsEnvSourcedWithoutDefault() throws IOException {
        String value = loadProperties().getProperty("jwt.secret");
        assertThat(value).as("jwt.secret must be present").isNotNull();
        assertThat(value)
                .as("jwt.secret must be a bare env placeholder (${JWT_SECRET}) with no committed default")
                .matches(BARE_ENV_PLACEHOLDER);
    }

    @Test
    void dbPasswordIsEnvSourcedWithoutDefault() throws IOException {
        String value = loadProperties().getProperty("spring.datasource.password");
        assertThat(value).as("spring.datasource.password must be present").isNotNull();
        assertThat(value)
                .as("DB password must be a bare env placeholder (${DB_PASSWORD}) with no committed default")
                .matches(BARE_ENV_PLACEHOLDER);
    }

    @Test
    void secretBearingPropertiesContainNoLiteralValue() throws IOException {
        Properties properties = loadProperties();
        // Any property whose key hints at a credential must resolve from the environment, never hold
        // a literal, guards against a future regression reintroducing an inline secret/default.
        for (String key : properties.stringPropertyNames()) {
            String lower = key.toLowerCase();
            if (lower.contains("password") || lower.contains("secret") || lower.endsWith(".key")) {
                String value = properties.getProperty(key);
                assertThat(value)
                        .as("credential property '%s' must be an env placeholder, not a literal", key)
                        .startsWith("${")
                        .doesNotContain(":"); // no inline default → no committed fallback secret
            }
        }
    }
}
