package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M4.2 acceptance (spec §4/§9/§11): a single transactional move keeps the persisted board, the
 * {@link Move} row, and {@code moveCount} mutually consistent; driving a game to a terminal state
 * sets {@code status}/{@code winnerId} and the human's W/L/D counters (Elo deferred to M7).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameMoveServiceTest {

    @Autowired
    GameService gameService;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    UserRepository users;

    @Autowired
    GameRules<OthelloState, OthelloMove> rules;

    @Autowired
    GameStateMapper mapper;

    private UUID humanId;

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "hash")).getId();
    }

    @Test
    void appliesLegalMoveConsistentlyWithinOneTransaction() {
        // Human plays Black (bot White), so the freshly created game is Black-to-move at the initial
        // position — no AI reply yet (that arrives in M4.3).
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
        OthelloState initial = OthelloState.initial();
        OthelloMove move = rules.getLegalMoves(initial).get(0);

        GameStateResponse response = gameService.applyMove(gameId, move);

        Game stored = games.findById(gameId).orElseThrow();
        assertThat(stored.getMoveCount()).isEqualTo(1);
        assertThat(stored.getCurrentTurn()).isEqualTo(Player.WHITE);
        assertThat(stored.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        OthelloState expected = rules.applyMove(initial, move);
        assertThat(stored.getBoardBlack()).isEqualTo(expected.black());
        assertThat(stored.getBoardWhite()).isEqualTo(expected.white());
        assertThat(response.moveCount()).isEqualTo(1);
        assertThat(response.currentTurn()).isEqualTo(Player.WHITE);

        List<Move> history = moves.findByGameIdOrderByMoveNumberAsc(gameId);
        assertThat(history).hasSize(1);
        Move recorded = history.get(0);
        assertThat(recorded.getMoveNumber()).isEqualTo(1);
        assertThat(recorded.getPlayer()).isEqualTo(Player.BLACK);
        assertThat(recorded.isPass()).isFalse();
        assertThat(recorded.getPosition().intValue()).isEqualTo(move.square());
        // Board ≡ move list: initial black, plus the placed square, plus the flips, equals the store.
        assertThat(initial.black() | OthelloState.bit(move.square()) | recorded.getFlippedMask())
                .isEqualTo(stored.getBoardBlack());
    }

    @Test
    void playingToTerminalResolvesOutcomeAndHumanCounters() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();

        // Drive both sides deterministically (first legal move; pass only when forced) through the
        // same service primitive until the game ends.
        Game game = games.findById(gameId).orElseThrow();
        int guard = 0;
        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
            gameService.applyMove(gameId, legal.isEmpty() ? OthelloMove.pass() : legal.get(0));
            game = games.findById(gameId).orElseThrow();
            assertThat(++guard).isLessThan(200);
        }

        // status matches the disc winner.
        Optional<Player> winner = rules.winner(mapper.toState(game));
        GameStatus expectedStatus = winner.map(p -> p == Player.BLACK ? GameStatus.BLACK_WON : GameStatus.WHITE_WON)
                .orElse(GameStatus.DRAW);
        assertThat(game.getStatus()).isEqualTo(expectedStatus);

        // Human is Black: winnerId is the human only on a Black win; null when the bot (White) wins or
        // it's a draw.
        if (game.getStatus() == GameStatus.BLACK_WON) {
            assertThat(game.getWinnerId()).isEqualTo(humanId);
        } else {
            assertThat(game.getWinnerId()).isNull();
        }

        // Exactly one of the human's W/L/D incremented, consistent with the outcome; gamesPlayed == 1.
        User human = users.findById(humanId).orElseThrow();
        assertThat(human.getGamesPlayed()).isEqualTo(1);
        assertThat(human.getWins() + human.getLosses() + human.getDraws()).isEqualTo(1);
        switch (game.getStatus()) {
            case BLACK_WON -> assertThat(human.getWins()).isEqualTo(1);
            case WHITE_WON -> assertThat(human.getLosses()).isEqualTo(1);
            case DRAW -> assertThat(human.getDraws()).isEqualTo(1);
            default -> throw new AssertionError("unexpected terminal status " + game.getStatus());
        }

        // Replay the recorded history from the initial position; it must reproduce the stored board.
        OthelloState replay = OthelloState.initial();
        for (Move recorded : moves.findByGameIdOrderByMoveNumberAsc(gameId)) {
            replay = rules.applyMove(
                    replay, recorded.isPass() ? OthelloMove.pass() : OthelloMove.at(recorded.getPosition()));
        }
        assertThat(replay.black()).isEqualTo(game.getBoardBlack());
        assertThat(replay.white()).isEqualTo(game.getBoardWhite());

        // A move on the now-terminal game is refused (409) and must not re-resolve the counters.
        assertThatThrownBy(() -> gameService.applyMove(gameId, OthelloMove.pass()))
                .isInstanceOf(GameNotInProgressException.class);
        User after = users.findById(humanId).orElseThrow();
        assertThat(after.getGamesPlayed()).isEqualTo(1);
        assertThat(after.getWins() + after.getLosses() + after.getDraws()).isEqualTo(1);
    }
}
