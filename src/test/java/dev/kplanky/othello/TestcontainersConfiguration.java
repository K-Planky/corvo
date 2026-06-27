package dev.kplanky.othello;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Testcontainers wiring for integration tests. The {@link ServiceConnection}-annotated
 * container supplies the DataSource connection details, overriding {@code spring.datasource.*}, so
 * every {@code @SpringBootTest} that imports this runs against a real Postgres 16 with Flyway applied.
 *
 * <p>Uses the Testcontainers 2.x {@code org.testcontainers.postgresql.PostgreSQLContainer} (the
 * {@code org.testcontainers.containers} variant is deprecated in 2.x).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
