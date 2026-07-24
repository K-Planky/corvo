package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M9.3 acceptance (spec §11): the concurrent-move race, proven under real human-vs-human play. Two
 * simultaneous {@link GameService#submitMove} calls on the same game, both by the on-turn player (a
 * double-click / two-tab hazard that PvP makes real), resolve as exactly one success and one
 * {@link ConcurrentMoveException} (409). The loser's transaction rolls back, so the board is left
 * exactly as the winning move set it: a single coherent move, never a mix.
 *
 * <p>Unlike the vs-AI M4.6 race (where only one writer inserts a Move row), here <em>both</em>
 * submissions target {@code move_number = 1}, so the conflict trips whichever guard the flush hits
 * first, the {@code (game_id, move_number)} unique index or the stale {@code @Version}, both of
 * which {@code submitMove} maps to 409.
 *
 * <p>Not {@code @Transactional}: each writer needs its own committed transaction on its own thread.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PvpConcurrentMoveTest {

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

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private UUID blackId;
    private UUID gameId;

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();
        tx = new TransactionTemplate(txManager);

        blackId = users.save(new User("black", "hash")).getId();
        UUID whiteId = users.save(new User("white", "hash")).getId();

        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_HUMAN);
        game.setBlackPlayerId(blackId);
        game.setWhitePlayerId(whiteId);
        mapper.writeState(game, OthelloState.initial()); // Black to move, opening position
        gameId = games.save(game).getId();
    }

    @Test
    void twoSimultaneousSubmissionsResolveAsOneSuccessAndOne409WithBoardIntact() throws Exception {
        // Two DISTINCT legal opening moves for Black, both pass authorization (Black's turn, both
        // legal), so the race is decided at the write, not at the anti-cheat.
        List<OthelloMove> legal = rules.getLegalMoves(OthelloState.initial());
        OthelloMove moveA = legal.get(0);
        OthelloMove moveB = legal.get(1);
        long boardA_black = rules.applyMove(OthelloState.initial(), moveA).black();
        long boardB_black = rules.applyMove(OthelloState.initial(), moveB).black();

        CountDownLatch blockerHasLock = new CountDownLatch(1);
        CountDownLatch blockerMayRelease = new CountDownLatch(1);

        // A blocker holds the game row's write lock (SELECT ... FOR UPDATE) without touching @Version.
        // Both real submitMove calls read version 0 (MVCC, non-blocking) and apply, then stall at their
        // flush behind this lock, so both provably raced from the same version before either wrote.
        Thread blocker = new Thread(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("select 1 from games where id = ? for update", Integer.class, gameId);
            blockerHasLock.countDown();
            await(blockerMayRelease);
        }));
        blocker.start();
        await(blockerHasLock);

        AtomicReference<GameStateResponse> success = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread racerA = submitter(moveA, success, failure);
        Thread racerB = submitter(moveB, success, failure);
        racerA.start();
        racerB.start();

        // Wait until both racers are parked on a lock (one on the game row UPDATE, one behind the other's
        // uncommitted (game_id, 1) Move insert), proof both read version 0, then release the blocker.
        awaitBlockedBackends(2);
        blockerMayRelease.countDown();

        blocker.join();
        racerA.join();
        racerB.join();

        // Exactly one 200 and one 409.
        assertThat(success.get()).as("one submission succeeds").isNotNull();
        assertThat(failure.get()).as("the other loses with 409").isInstanceOf(ConcurrentMoveException.class);

        // The board is exactly the winner's single move, never a mix, never both applied.
        Game finalState = games.findById(gameId).orElseThrow();
        assertThat(finalState.getMoveCount()).isEqualTo(1);
        assertThat(moves.findByGameIdOrderByMoveNumberAsc(gameId)).hasSize(1);
        assertThat(finalState.getBoardBlack())
                .isEqualTo(success.get().boardBlack())
                .isIn(boardA_black, boardB_black);
    }

    /** A thread that submits {@code move} as Black, recording either the response or the thrown error. */
    private Thread submitter(
            OthelloMove move, AtomicReference<GameStateResponse> success, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                GameStateResponse response = gameService.submitMove(gameId, blackId, move);
                success.set(response);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
    }

    /**
     * Blocks until {@code n} backends are waiting on a lock (each racer parked at its flush). The blocker
     * itself sits {@code idle in transaction}, so it doesn't count, only the {@code active} racers on a
     * {@code Lock} wait event do. Makes the conflict deterministic without a timing guess.
     */
    private void awaitBlockedBackends(int n) {
        for (int i = 0; i < 200; i++) {
            Long blocked = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where state = 'active' "
                            + "and wait_event_type = 'Lock'",
                    Long.class);
            if (blocked != null && blocked >= n) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("racers never both blocked on the game row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting on latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
