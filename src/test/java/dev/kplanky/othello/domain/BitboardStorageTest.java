package dev.kplanky.othello.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.engine.Player;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2.2 acceptance (spec §5, signed-storage caveat): a 64-bit bitboard can set bit 63 (square h8),
 * which exceeds {@code 2^63 - 1}. Java {@code long} and Postgres {@code BIGINT} are both signed
 * two's-complement, so the value round-trips exactly, it just shows up as a <em>negative</em>
 * number in the database. This test proves the round-trip is lossless <em>and</em> that the stored
 * value really is negative (read back through raw JDBC, bypassing the ORM).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class BitboardStorageTest {

    /** Only bit 63 (h8) set: {@code 0x8000_0000_0000_0000} == {@link Long#MIN_VALUE}, a negative long. */
    private static final long H8_ONLY = 1L << 63;

    /** A full board (all 64 squares occupied): {@code -1L}, the all-ones bit pattern. */
    private static final long FULL_BOARD = -1L;

    @PersistenceContext
    EntityManager em;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void bit63BoardRoundTripsAndIsStoredNegative() {
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_AI);
        game.setBotSide(BotSide.BLACK);
        game.setBotDifficulty(BotDifficulty.MEDIUM);
        game.setBoardBlack(H8_ONLY);
        game.setBoardWhite(FULL_BOARD);
        game.setCurrentTurn(Player.WHITE);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setMoveCount(0);

        em.persist(game);
        em.flush();
        UUID id = game.getId();
        em.clear();

        // ORM round-trip is lossless: the exact long comes back.
        Game loaded = em.find(Game.class, id);
        assertThat(loaded.getBoardBlack()).isEqualTo(H8_ONLY);
        assertThat(loaded.getBoardWhite()).isEqualTo(FULL_BOARD);

        // And the value is genuinely stored as a negative BIGINT (read via raw JDBC, not the ORM).
        Long storedBlack =
                jdbcTemplate.queryForObject("SELECT board_black FROM games WHERE id = ?", Long.class, id);
        Long storedWhite =
                jdbcTemplate.queryForObject("SELECT board_white FROM games WHERE id = ?", Long.class, id);
        assertThat(storedBlack).isEqualTo(Long.MIN_VALUE).isNegative();
        assertThat(storedWhite).isEqualTo(-1L).isNegative();
    }

    @Test
    void moveFlippedMaskWithBit63RoundTrips() {
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_AI);
        game.setBotSide(BotSide.WHITE);
        game.setBoardBlack(0L);
        game.setBoardWhite(0L);
        game.setCurrentTurn(Player.BLACK);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setMoveCount(0);
        em.persist(game);

        // flippedMask is the same signed-BIGINT bitboard storage; flipping h8 sets bit 63.
        Move move = Move.placement(game.getId(), 1, Player.BLACK, 62, H8_ONLY);
        em.persist(move);
        em.flush();
        UUID id = move.getId();
        em.clear();

        Move loaded = em.find(Move.class, id);
        assertThat(loaded.getFlippedMask()).isEqualTo(H8_ONLY);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT flipped_mask FROM moves WHERE id = ?", Long.class, id))
                .isEqualTo(Long.MIN_VALUE)
                .isNegative();
    }
}
