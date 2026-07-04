package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Elo overhaul (spec §8): vs-AI games are unrated practice. A terminal vs-AI game ends and sets its
 * status, but leaves no competitive trace — the human's Elo is unchanged, no {@link RatingHistory} row
 * is written, and W/L/D / games-played stay zero. (PvP rating is covered by the WebSocket PvP tests.)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameEloTest {

    @Autowired
    GameService gameService;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    UserRepository users;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    GameRules<OthelloState, OthelloMove> rules;

    @Autowired
    GameStateMapper mapper;

    private UUID humanId;

    @BeforeEach
    void setUp() {
        ratings.deleteAll();
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "hash")).getId();
    }

    @Test
    void terminalVsAiGameIsUnratedAndRecordsNothing() {
        // Human plays Black (bot White) so we can drive both sides deterministically via applyMove.
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.MEDIUM, BotSide.WHITE).id();

        Game game = playToTerminal(gameId);

        // The game really finished (status set), but it's practice — nothing competitive was recorded.
        assertThat(game.getStatus()).isNotEqualTo(GameStatus.IN_PROGRESS);

        // Elo unchanged from the starting value; no rating history written.
        User human = users.findById(humanId).orElseThrow();
        assertThat(human.getEloRating()).isEqualTo(1200);
        assertThat(ratings.findAll()).isEmpty();

        // W/L/D and games-played untouched — a bot game leaves no record.
        assertThat(human.getGamesPlayed()).isZero();
        assertThat(human.getWins()).isZero();
        assertThat(human.getLosses()).isZero();
        assertThat(human.getDraws()).isZero();
    }

    /** Drives both sides with the first legal move (pass only when forced) until the game ends. */
    private Game playToTerminal(UUID gameId) {
        Game game = games.findById(gameId).orElseThrow();
        int guard = 0;
        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
            gameService.applyMove(gameId, legal.isEmpty() ? OthelloMove.pass() : legal.get(0));
            game = games.findById(gameId).orElseThrow();
            assertThat(++guard).isLessThan(200);
        }
        return game;
    }
}
