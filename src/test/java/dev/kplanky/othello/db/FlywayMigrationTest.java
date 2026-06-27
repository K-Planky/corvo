package dev.kplanky.othello.db;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M0.2 acceptance: proves the DB + Flyway + Testcontainers path end-to-end. The app boots against a
 * real Postgres (Testcontainers), Flyway applies the V1 baseline on startup, and we assert it was
 * recorded as a successful migration in {@code flyway_schema_history}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void baselineMigrationIsAppliedAgainstPostgres() {
        // Flyway ran on startup against a real Postgres (Testcontainers @ServiceConnection) and
        // recorded the V1 baseline as the first, successful migration. These queries also prove the
        // DataSource is reachable end-to-end.
        String firstVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE installed_rank = 1", String.class);
        assertThat(firstVersion).isEqualTo("1");

        Boolean v1Success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
        assertThat(v1Success).isTrue();
    }
}
