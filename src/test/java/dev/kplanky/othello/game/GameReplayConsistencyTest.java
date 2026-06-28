package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M4.7 acceptance (spec §5): the intentional board + move-list redundancy. Re-applying a game's
 * persisted {@link Move} list from {@link GameRules#initialState()} must reproduce the stored
 * {@code boardBlack}/{@code boardWhite} exactly — this is what makes a game replayable/resumable from
 * its move history alone. Each move's redundant {@code flippedMask} column is checked the same way: it
 * must equal the flips recomputed during replay, so the move list is internally self-consistent and
 * not merely coincident with the snapshot.
 *
 * <p>Games are driven through the real {@link GameService#submitMove} path against the random bot, so
 * the histories carry genuine variety — placements in every direction and the occasional forced pass.
 * Several full games are replayed to exercise that variety rather than a single fixed line.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameReplayConsistencyTest {

    @Autowired
    GameService gameService;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

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
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "human@example.com", "hash")).getId();
    }

    @Test
    void replayingPersistedMovesFromInitialStateReproducesStoredBoard() {
        // Several full games against the random bot: the bot's randomness gives each history a
        // different shape (and across runs, forced passes), so replay is exercised over real variety.
        for (int g = 0; g < 8; g++) {
            UUID gameId = playFullGame();
            assertReplayReproducesStoredBoard(gameId);
        }
    }

    /**
     * Plays a vs-AI game (human Black, random bot White) to a terminal state through the production
     * submit path: each {@link GameService#submitMove} applies the human's move and the bot's random
     * reply. The human plays a deterministic move (first legal, or pass when forced) so the only
     * source of variation is the bot.
     */
    private UUID playFullGame() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
        Game game = games.findById(gameId).orElseThrow();
        int guard = 0;
        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
            gameService.submitMove(gameId, humanId, legal.isEmpty() ? OthelloMove.pass() : legal.get(0));
            game = games.findById(gameId).orElseThrow();
            assertThat(++guard).isLessThan(200);
        }
        return gameId;
    }

    /**
     * Replays {@code gameId}'s ordered move list from the engine's initial position and asserts the
     * result is bit-for-bit the stored board, that each persisted {@code flippedMask} matches the
     * flips recomputed during replay, and that the side to move + move count agree with the snapshot.
     */
    private void assertReplayReproducesStoredBoard(UUID gameId) {
        Game stored = games.findById(gameId).orElseThrow();
        List<Move> history = moves.findByGameIdOrderByMoveNumberAsc(gameId);
        assertThat(history).hasSize(stored.getMoveCount());

        OthelloState replay = rules.initialState();
        for (Move recorded : history) {
            // The move list orders moves 1..n with alternating sides; the persisted player must be the
            // side to move at this point in the replay (catches any ordering/turn corruption).
            assertThat(recorded.getPlayer()).isEqualTo(rules.currentPlayer(replay));

            OthelloMove move = recorded.isPass() ? OthelloMove.pass() : OthelloMove.at(recorded.getPosition());
            OthelloState before = replay;
            replay = rules.applyMove(replay, move);

            // The redundant flippedMask column must equal the flips this move actually produced:
            // opponent discs before the move that belong to the mover after it (the placed square was
            // empty, so it is naturally excluded). A pass flips nothing.
            long recomputedFlips = recorded.isPass()
                    ? 0L
                    : before.discs(before.toMove().opponent()) & replay.discs(before.toMove());
            assertThat(recorded.getFlippedMask()).isEqualTo(recomputedFlips);
        }

        // The whole point of the board/move-list redundancy: the replayed board equals the O(1)
        // snapshot, and the derived turn agrees too.
        assertThat(replay.black()).isEqualTo(stored.getBoardBlack());
        assertThat(replay.white()).isEqualTo(stored.getBoardWhite());
        assertThat(replay.toMove()).isEqualTo(stored.getCurrentTurn());
    }
}
