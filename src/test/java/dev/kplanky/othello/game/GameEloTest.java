package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.RatingHistory;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.rating.Elo;
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
 * M7.1 acceptance (spec §8): a terminal vs-AI game moves the human's Elo by the expected delta
 * against the bot's fixed rating, leaves the bot rating untouched, and writes a {@link RatingHistory}
 * row. The bot has no {@code User} row, so only the human's rating moves.
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
        humanId = users.save(new User("human", "human@example.com", "hash")).getId();
    }

    @Test
    void terminalVsAiGameMovesHumanEloAndWritesHistory() {
        int botRating = BotDifficulty.MEDIUM.rating(); // 1500
        // Human plays Black (bot White) so we can drive both sides deterministically via applyMove,
        // bypassing the synchronous bot reply — the human is the only rated participant.
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.MEDIUM, BotSide.WHITE).id();

        Game game = playToTerminal(gameId);

        double score = switch (game.getStatus()) {
            case BLACK_WON -> Elo.WIN; // human is Black
            case WHITE_WON -> Elo.LOSS;
            case DRAW -> Elo.DRAW;
            default -> throw new AssertionError("game not terminal: " + game.getStatus());
        };
        int expected = Elo.updatedRating(1200, botRating, score);

        // The human's rating moved by exactly the Elo delta against the bot's fixed rating.
        User human = users.findById(humanId).orElseThrow();
        assertThat(human.getEloRating()).isEqualTo(expected);

        // The bot rating is fixed: never updated (it's stored on the game, not on any User row).
        assertThat(game.getBotRating()).isEqualTo(botRating);

        // Exactly one RatingHistory row — for the human only — capturing the before/after/delta.
        List<RatingHistory> history = ratings.findAll();
        assertThat(history).hasSize(1);
        RatingHistory rh = history.get(0);
        assertThat(rh.getUserId()).isEqualTo(humanId);
        assertThat(rh.getGameId()).isEqualTo(gameId);
        assertThat(rh.getOldRating()).isEqualTo(1200);
        assertThat(rh.getNewRating()).isEqualTo(expected);
        assertThat(rh.getDelta()).isEqualTo(expected - 1200);
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
