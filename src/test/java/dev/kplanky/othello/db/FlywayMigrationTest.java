package dev.kplanky.othello.db;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M0.2 + M2.4 acceptance: proves the DB + Flyway + Testcontainers path end-to-end. The app boots
 * against a real Postgres (Testcontainers), Flyway applies V1 (baseline) then V2 (core schema) on
 * startup, and we assert both were recorded as successful migrations in {@code flyway_schema_history}
 * and that V2's core schema objects (tables, unique constraints, the gameId-leading index) exist.
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

    @Test
    void coreSchemaMigrationIsApplied() {
        // V2 applied cleanly, immediately after V1 (V1 + V2 apply on a fresh Postgres).
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history WHERE installed_rank = 2", String.class))
                .isEqualTo("2");

        // All four core tables exist.
        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("games")).isTrue();
        assertThat(tableExists("moves")).isTrue();
        assertThat(tableExists("rating_history")).isTrue();

        // Unique constraints: username unique; moveNumber unique per game.
        assertThat(uniqueConstraintExists("uq_users_username")).isTrue();
        assertThat(uniqueConstraintExists("uq_moves_game_move_number")).isTrue();

        // gameId is indexed: the composite unique index leads with game_id, so it serves
        // WHERE game_id = ? lookups (no separate single-column index needed).
        String movesIndexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'moves' "
                        + "AND indexname = 'uq_moves_game_move_number'",
                String.class);
        assertThat(movesIndexDef).contains("(game_id, move_number)");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private boolean uniqueConstraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND constraint_type = 'UNIQUE' "
                        + "AND constraint_name = ?",
                Integer.class,
                constraintName);
        return count != null && count == 1;
    }
}
