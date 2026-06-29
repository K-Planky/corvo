package dev.kplanky.othello.engine;

import dev.kplanky.othello.engine.othello.OthelloEvaluator;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloMoveOrdering;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Iterative deepening + time budget (spec §7, M6 rung 5). The two high-risk behaviours the Done-when
 * names are pinned deterministically with an injected out-of-time predicate (no wall-clock flakiness):
 * the search <b>returns the move from the last fully completed depth</b> (partial depths discarded),
 * always answers with a legal move however small the budget, and deepens monotonically as the budget
 * grows. A single loose-tolerance wall-clock test covers the real {@link Duration} budget path, and a
 * fuzz proves the AI never returns an illegal move.
 */
class IterativeDeepeningSearchTest {

    private final OthelloRules rules = new OthelloRules();
    private final OthelloEvaluator evaluator = new OthelloEvaluator();
    private final MoveOrdering<OthelloState, OthelloMove> ordering = new OthelloMoveOrdering();

    private static final BooleanSupplier NEVER = () -> false;

    private static long sq(int square) {
        return 1L << square;
    }

    // --- Fixed-depth correctness: completes every depth and returns an optimal move -------------

    @Test
    void withUnlimitedTimeItCompletesToMaxDepthAndReturnsAnOptimalMove() {
        OthelloState initial = rules.initialState();
        int maxDepth = 5;

        IterativeDeepeningSearch<OthelloState, OthelloMove> id =
                new IterativeDeepeningSearch<>(rules, evaluator, ordering, NEVER, maxDepth);
        var progress = id.deepen(initial);

        assertThat(progress.completedDepth()).isEqualTo(maxDepth);
        assertThat(rules.getLegalMoves(initial)).contains(progress.move());
        assertThat(moveValue(initial, progress.move(), maxDepth)).isEqualTo(optimum(initial, maxDepth));
    }

    // --- Always returns a legal move, even with zero budget -------------------------------------

    @Test
    void withNoTimeItStillReturnsTheCompletedDepthOneMove() {
        OthelloState initial = rules.initialState();
        BooleanSupplier alwaysOut = () -> true;

        var progress = new IterativeDeepeningSearch<>(rules, evaluator, ordering, alwaysOut, 8)
                .deepen(initial);

        // Depth 1 runs uninterruptibly, so we still get a legal, depth-1-optimal move — and nothing
        // deeper (depth 2 is abandoned before it starts).
        assertThat(progress.completedDepth()).isEqualTo(1);
        assertThat(rules.getLegalMoves(initial)).contains(progress.move());
        assertThat(moveValue(initial, progress.move(), 1)).isEqualTo(optimum(initial, 1));
    }

    // --- Deepens monotonically with budget; every result is optimal at its completed depth ------

    @Test
    void biggerBudgetReachesDeeperAndNeverReturnsAPartialDepthsMove() {
        OthelloState state = midGamePosition();
        int maxDepth = 6;

        int previousDepth = 0;
        boolean sawAnIntermediateDepth = false;
        for (int budget : new int[] {0, 3, 12, 60, 300, 5000, Integer.MAX_VALUE}) {
            // Out of time after `budget` polls — a deterministic stand-in for the wall clock.
            BooleanSupplier outOfTime = afterNCalls(budget);
            var progress = new IterativeDeepeningSearch<>(rules, evaluator, ordering, outOfTime, maxDepth)
                    .deepen(state);

            int depth = progress.completedDepth();
            assertThat(depth).as("deepening is monotonic in the budget").isGreaterThanOrEqualTo(previousDepth);
            assertThat(depth).isBetween(1, maxDepth);
            assertThat(rules.getLegalMoves(state)).contains(progress.move());
            // The crux: the returned move is optimal for its *completed* depth — never a partial,
            // suboptimal result leaked from the aborted next depth.
            assertThat(moveValue(state, progress.move(), depth))
                    .as("move is optimal at the last completed depth %d", depth)
                    .isEqualTo(optimum(state, depth));
            if (depth > 1 && depth < maxDepth) {
                sawAnIntermediateDepth = true;
            }
            previousDepth = depth;
        }
        // The scan actually exercised a budget-limited stop between the floor and the cap.
        assertThat(sawAnIntermediateDepth).as("an intermediate budget-limited depth was hit").isTrue();
        assertThat(previousDepth).isEqualTo(maxDepth); // an unbounded budget completes the cap
    }

    // --- The principal-variation hint puts the previous best move first --------------------------

    @Test
    void hintFirstTriesThePreviousBestBeforeEverythingElse() {
        List<OthelloMove> moves = List.of(OthelloMove.at(0), OthelloMove.at(19), OthelloMove.at(9));
        OthelloMove hint = OthelloMove.at(19); // a quiet square the static ordering would rank below a1

        var hinted = MoveOrdering.hintFirst(hint, ordering);
        List<OthelloMove> ordered = hinted.order(null, moves);

        assertThat(ordered).first().isEqualTo(hint);
        assertThat(ordered).containsExactlyInAnyOrderElementsOf(moves); // a permutation
        // A hint that isn't among the moves is a no-op — falls through to the base ordering.
        assertThat(MoveOrdering.hintFirst(OthelloMove.at(5), ordering).order(null, moves))
                .isEqualTo(ordering.order(null, moves));
    }

    // --- Never returns an illegal move (fuzz) ---------------------------------------------------

    @Test
    void neverReturnsAnIllegalMoveAcrossRandomPositions() {
        Random rng = new Random(20260629L);
        int checked = 0;
        for (int i = 0; i < 250; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(60));
            if (rules.isTerminal(state) || rules.getLegalMoves(state).isEmpty()) {
                continue; // a forced pass is the caller's job, not the search's
            }
            OthelloMove move = new IterativeDeepeningSearch<>(rules, evaluator, ordering, NEVER, 3)
                    .bestMove(state);
            assertThat(rules.getLegalMoves(state))
                    .as("iterative deepening returned a legal move for %s", state)
                    .contains(move);
            checked++;
        }
        assertThat(checked).isGreaterThan(100);
    }

    // --- Real wall-clock budget is respected within tolerance -----------------------------------

    @Test
    void respectsTheWallClockBudgetWithinTolerance() {
        OthelloState initial = rules.initialState();
        Duration budget = Duration.ofMillis(120);

        long startNanos = System.nanoTime();
        var progress = new IterativeDeepeningSearch<>(rules, evaluator, ordering, budget).deepen(initial);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(rules.getLegalMoves(initial)).contains(progress.move());
        assertThat(progress.completedDepth()).as("it deepened past the guaranteed depth 1").isGreaterThan(1);
        // Aborts are polled per node, so overshoot is small; allow generous slack for CI jitter/GC.
        assertThat(elapsedMillis).as("finished near the budget, not far past it").isLessThan(700);
    }

    // --- Argument validation --------------------------------------------------------------------

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> new IterativeDeepeningSearch<>(rules, evaluator, ordering, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new IterativeDeepeningSearch<>(rules, evaluator, ordering, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IterativeDeepeningSearch<>(rules, evaluator, ordering, NEVER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** A predicate that returns {@code false} for the first {@code n} polls, then {@code true} forever. */
    private static BooleanSupplier afterNCalls(int n) {
        int[] remaining = {n};
        return () -> remaining[0]-- <= 0;
    }

    /** A reproducible mid-game position with several legal moves. */
    private OthelloState midGamePosition() {
        return randomPosition(new Random(99L), 20);
    }

    /** A legal position reached by playing {@code plies} uniformly random legal moves (auto-passing). */
    private OthelloState randomPosition(Random rng, int plies) {
        OthelloState state = rules.initialState();
        for (int i = 0; i < plies; i++) {
            if (rules.isTerminal(state)) {
                break;
            }
            List<OthelloMove> moves = rules.getLegalMoves(state);
            state = moves.isEmpty()
                    ? rules.pass(state)
                    : rules.applyMove(state, moves.get(rng.nextInt(moves.size())));
        }
        return state;
    }

    /** Best achievable root value at {@code depth}, by the same evaluator the search uses. */
    private int optimum(OthelloState state, int depth) {
        int best = -Integer.MAX_VALUE;
        for (OthelloMove move : rules.getLegalMoves(state)) {
            best = Math.max(best, moveValue(state, move, depth));
        }
        return best;
    }

    private int moveValue(OthelloState state, OthelloMove move, int depth) {
        return -negamaxValue(rules.applyMove(state, move), depth - 1);
    }

    private int negamaxValue(OthelloState state, int depth) {
        if (depth == 0 || rules.isTerminal(state)) {
            return evaluator.evaluate(state, rules.currentPlayer(state));
        }
        List<OthelloMove> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            return -negamaxValue(rules.pass(state), depth - 1);
        }
        int best = -Integer.MAX_VALUE;
        for (OthelloMove move : moves) {
            best = Math.max(best, -negamaxValue(rules.applyMove(state, move), depth - 1));
        }
        return best;
    }
}
