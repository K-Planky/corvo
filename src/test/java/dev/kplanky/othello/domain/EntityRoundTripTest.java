package dev.kplanky.othello.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.engine.Player;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2.1 acceptance (spec §5): each JPA entity persists and reloads with all fields intact against a
 * real Postgres (Testcontainers) on the Flyway-managed V2 schema. Because {@code ddl-auto=validate},
 * the app context only starts if every entity mapping matches {@code V2__core_schema.sql}.
 *
 * <p>Each test persists, then {@code flush()} + {@code clear()} to evict the persistence context, so
 * the subsequent {@code find()} is a genuine reload from the database, not a first-level-cache hit.
 * The method-level {@code @Transactional} rolls everything back afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class EntityRoundTripTest {

    @PersistenceContext
    EntityManager em;

    @Test
    void userRoundTrips() {
        User user = new User("alice", "alice@example.com", "$2a$bcrypt-hash");
        user.setEloRating(1234);
        user.setGamesPlayed(10);
        user.setWins(6);
        user.setLosses(3);
        user.setDraws(1);

        em.persist(user);
        em.flush();
        UUID id = user.getId();
        em.clear();

        User loaded = em.find(User.class, id);
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getUsername()).isEqualTo("alice");
        assertThat(loaded.getEmail()).isEqualTo("alice@example.com");
        assertThat(loaded.getPasswordHash()).isEqualTo("$2a$bcrypt-hash");
        assertThat(loaded.getEloRating()).isEqualTo(1234);
        assertThat(loaded.getGamesPlayed()).isEqualTo(10);
        assertThat(loaded.getWins()).isEqualTo(6);
        assertThat(loaded.getLosses()).isEqualTo(3);
        assertThat(loaded.getDraws()).isEqualTo(1);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void gameRoundTrips() {
        User human = persistedUser("bob");

        Game game = new Game();
        game.setBlackPlayerId(human.getId());
        game.setWhitePlayerId(null); // bot plays White
        game.setOpponentType(OpponentType.HUMAN_VS_AI);
        game.setBotSide(BotSide.WHITE);
        game.setBotDifficulty(BotDifficulty.HARD);
        game.setBoardBlack(0x0000000810000000L);
        game.setBoardWhite(0x0000001008000000L);
        game.setCurrentTurn(Player.BLACK);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setWinnerId(null);
        game.setMoveCount(0);

        em.persist(game);
        em.flush();
        UUID id = game.getId();
        em.clear();

        Game loaded = em.find(Game.class, id);
        assertThat(loaded.getBlackPlayerId()).isEqualTo(human.getId());
        assertThat(loaded.getWhitePlayerId()).isNull();
        assertThat(loaded.getOpponentType()).isEqualTo(OpponentType.HUMAN_VS_AI);
        assertThat(loaded.getBotSide()).isEqualTo(BotSide.WHITE);
        assertThat(loaded.getBotDifficulty()).isEqualTo(BotDifficulty.HARD);
        assertThat(loaded.getBoardBlack()).isEqualTo(0x0000000810000000L);
        assertThat(loaded.getBoardWhite()).isEqualTo(0x0000001008000000L);
        assertThat(loaded.getCurrentTurn()).isEqualTo(Player.BLACK);
        assertThat(loaded.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(loaded.getWinnerId()).isNull();
        assertThat(loaded.getMoveCount()).isZero();
        assertThat(loaded.getVersion()).isZero(); // @Version initialised on first persist
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void movePlacementRoundTrips() {
        Game game = persistedGame(persistedUser("carol"));

        Move move = Move.placement(game.getId(), 1, Player.BLACK, 19, 0x0000000008000000L);

        em.persist(move);
        em.flush();
        UUID id = move.getId();
        em.clear();

        Move loaded = em.find(Move.class, id);
        assertThat(loaded.getGameId()).isEqualTo(game.getId());
        assertThat(loaded.getMoveNumber()).isEqualTo(1);
        assertThat(loaded.getPlayer()).isEqualTo(Player.BLACK);
        assertThat(loaded.getPosition()).isEqualTo((short) 19);
        assertThat(loaded.isPass()).isFalse();
        assertThat(loaded.getFlippedMask()).isEqualTo(0x0000000008000000L);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void movePassRoundTrips() {
        Game game = persistedGame(persistedUser("dave"));

        Move move = Move.pass(game.getId(), 2, Player.WHITE);

        em.persist(move);
        em.flush();
        UUID id = move.getId();
        em.clear();

        Move loaded = em.find(Move.class, id);
        assertThat(loaded.getMoveNumber()).isEqualTo(2);
        assertThat(loaded.getPlayer()).isEqualTo(Player.WHITE);
        assertThat(loaded.getPosition()).isNull();
        assertThat(loaded.isPass()).isTrue();
        assertThat(loaded.getFlippedMask()).isZero();
    }

    @Test
    void ratingHistoryRoundTrips() {
        User human = persistedUser("erin");
        Game game = persistedGame(human);

        RatingHistory history = new RatingHistory(human.getId(), game.getId(), 1200, 1216);

        em.persist(history);
        em.flush();
        UUID id = history.getId();
        em.clear();

        RatingHistory loaded = em.find(RatingHistory.class, id);
        assertThat(loaded.getUserId()).isEqualTo(human.getId());
        assertThat(loaded.getGameId()).isEqualTo(game.getId());
        assertThat(loaded.getOldRating()).isEqualTo(1200);
        assertThat(loaded.getNewRating()).isEqualTo(1216);
        assertThat(loaded.getDelta()).isEqualTo(16);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    private User persistedUser(String username) {
        User user = new User(username, username + "@example.com", "$2a$hash");
        em.persist(user);
        return user;
    }

    private Game persistedGame(User blackPlayer) {
        Game game = new Game();
        game.setBlackPlayerId(blackPlayer.getId());
        game.setOpponentType(OpponentType.HUMAN_VS_AI);
        game.setBotSide(BotSide.WHITE);
        game.setBotDifficulty(BotDifficulty.EASY);
        game.setBoardBlack(0x0000000810000000L);
        game.setBoardWhite(0x0000001008000000L);
        game.setCurrentTurn(Player.BLACK);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setMoveCount(0);
        em.persist(game);
        return game;
    }
}
