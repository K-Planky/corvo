package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.UserRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M4.6 acceptance (spec §11): optimistic locking on move application. A move submission that races
 * another on the same {@link Game} loses the {@code @Version} check and is rejected — surfaced as a
 * 409 ({@link ConcurrentMoveException}) through the service — while the board is left exactly as the
 * winning move set it (the loser's transaction rolls back; no corruption).
 *
 * <p>Not {@code @Transactional}: each writer needs its own committed transaction, so the test drives
 * transactions explicitly on separate threads.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameConcurrencyTest {

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

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private UUID humanId;
    private UUID gameId;

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
        tx = new TransactionTemplate(txManager);
        humanId = users.save(new User("human", "human@example.com", "hash")).getId();
        // Human plays Black (bot White), so no bot opening move: the board is the engine initial
        // position and it's the human's turn.
        gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
    }

    @Test
    void staleVersionWriteIsRejectedAndBoardSurvives() throws Exception {
        // Two transactions both read version 0, then one commits before the other writes — the late
        // writer's UPDATE matches 0 rows (version moved on) and Hibernate raises the optimistic-lock
        // failure. Fully deterministic via latches; no timing assumptions.
        long winnerBoard = 0x00000000000000FFL;
        long loserBoard = 0xFF00000000000000L;
        CountDownLatch bothLoaded = new CountDownLatch(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        Thread winner = new Thread(() -> tx.executeWithoutResult(s -> {
            Game g = games.findById(gameId).orElseThrow();
            bothLoaded.countDown();
            await(bothLoaded);
            g.setBoardBlack(winnerBoard);
            g.setMoveCount(g.getMoveCount() + 1);
            games.saveAndFlush(g);
        }));
        Thread loser = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    Game g = games.findById(gameId).orElseThrow();
                    bothLoaded.countDown();
                    await(bothLoaded);
                    await(winnerCommitted); // let the winner commit version 1 first
                    g.setBoardBlack(loserBoard);
                    g.setMoveCount(g.getMoveCount() + 1);
                    games.saveAndFlush(g); // UPDATE ... WHERE version = 0 → 0 rows → conflict
                });
            } catch (Throwable t) {
                loserError.set(t);
            }
        });

        winner.start();
        loser.start();
        winner.join();
        winnerCommitted.countDown();
        loser.join();

        assertThat(loserError.get()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        Game finalState = games.findById(gameId).orElseThrow();
        assertThat(finalState.getBoardBlack()).isEqualTo(winnerBoard); // the loser's write never landed
    }

    @Test
    void racingSubmitMoveLosesWith409AndLeavesBoardIntact() throws Exception {
        OthelloMove move = rules.getLegalMoves(OthelloState.initial()).get(0);
        OthelloState winningBoard = rules.applyMove(OthelloState.initial(), move);
        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch holderMayCommit = new CountDownLatch(1);

        // A competitor holds an uncommitted UPDATE on the game: it has the row write lock and a staged
        // version bump (board = the winning move) but has not committed yet.
        Thread holder = new Thread(() -> tx.executeWithoutResult(s -> {
            Game g = games.findById(gameId).orElseThrow();
            mapper.writeState(g, winningBoard);
            g.setMoveCount(g.getMoveCount() + 1);
            games.saveAndFlush(g);
            holderHasLock.countDown();
            await(holderMayCommit);
        }));
        holder.start();
        await(holderHasLock);

        // A real submitMove now races: it reads version 0 (MVCC, the competitor is uncommitted), then
        // blocks at its own flush on the held row lock.
        AtomicReference<Throwable> racerError = new AtomicReference<>();
        Thread racer = new Thread(() -> {
            try {
                gameService.submitMove(gameId, humanId, move);
            } catch (Throwable t) {
                racerError.set(t);
            }
        });
        racer.start();

        // Wait until the racer's flush is actually blocked on the competitor's row lock (not a fixed
        // sleep): at that point it has provably read version 0, so releasing the competitor's commit
        // forces the stale-version conflict deterministically.
        awaitRacerBlockedOnRowLock();
        holderMayCommit.countDown();
        holder.join();
        racer.join();

        assertThat(racerError.get()).isInstanceOf(ConcurrentMoveException.class);
        Game finalState = games.findById(gameId).orElseThrow();
        assertThat(finalState.getBoardBlack()).isEqualTo(winningBoard.black());
        assertThat(finalState.getBoardWhite()).isEqualTo(winningBoard.white());
    }

    /**
     * Blocks until exactly the racer is waiting on a row lock — an {@code active} backend parked on a
     * {@code Lock} wait event (the holder sits {@code idle in transaction}, so it doesn't match). This
     * proves the racer already read version 0 and is stalled at its flush, making the conflict
     * deterministic without a timing guess.
     */
    private void awaitRacerBlockedOnRowLock() {
        for (int i = 0; i < 200; i++) {
            Long blocked = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where state = 'active' "
                            + "and wait_event_type = 'Lock'",
                    Long.class);
            if (blocked != null && blocked >= 1) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("racer never blocked on the game row lock");
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
}
