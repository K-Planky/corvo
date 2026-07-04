package dev.kplanky.othello.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Iterative-deepening, time-budgeted search (spec §7, M6 rung 5 — the top of the AI ladder). It runs
 * full-width {@link AlphaBetaSearch} to depth 1, then 2, then 3… reusing the previous iteration's
 * best move as the first move to try one ply deeper (the principal variation — see
 * {@link MoveOrdering#hintFirst}), until the time budget runs out. The move returned is always the
 * one from the <b>last fully completed depth</b>: a depth interrupted mid-search by the budget is
 * discarded, never returned half-finished.
 *
 * <p>Why deepen instead of searching one fixed depth: difficulty becomes a <b>time budget</b> rather
 * than a hardcoded depth, the search adapts to how busy the position is, and each completed shallow
 * pass produces the move-ordering hint that makes the next, deeper pass prune hard.
 *
 * <p><b>Always returns a legal move.</b> Depth 1 is run uninterruptibly, so however small the budget,
 * there is always a completed iteration to answer with — and it is a real legal move ({@code AlphaBetaSearch}
 * only ever returns a move from {@link GameRules#getLegalMoves}). Deeper iterations are abortable: the
 * budget is polled at every node and a deadline-exceeded pass throws {@link AlphaBetaSearch.SearchAborted},
 * which is caught here so the previous depth's move stands. A {@code maxDepth} cap bounds the search on
 * a near-empty board where even a generous budget could otherwise keep deepening past the game's end.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public final class IterativeDeepeningSearch<S, M> implements Search<S, M> {

    /** Comfortably above Othello's ~60-ply maximum, so the budget — not this — is the real limit. */
    private static final int DEFAULT_MAX_DEPTH = 64;

    private final GameRules<S, M> rules;
    private final Evaluator<S> evaluator;
    private final MoveOrdering<S, M> ordering;
    private final long budgetNanos;
    private final BooleanSupplier injectedAbort; // non-null only for deterministic tests
    private final int maxDepth;

    /** The move chosen plus the deepest fully completed search depth that produced it. */
    public record Progress<M>(M move, int completedDepth) {}

    /**
     * @param budget   wall-clock time budget per move; must be positive.
     * @param ordering the base move ordering deeper iterations build on (the previous best is tried
     *                 ahead of it).
     */
    public IterativeDeepeningSearch(GameRules<S, M> rules, Evaluator<S> evaluator,
                                    MoveOrdering<S, M> ordering, Duration budget) {
        this(rules, evaluator, ordering, budget, DEFAULT_MAX_DEPTH);
    }

    /**
     * As {@link #IterativeDeepeningSearch(GameRules, Evaluator, MoveOrdering, Duration)} but with an
     * explicit depth cap. A cap well below the game's ply count turns the search into a strength
     * ceiling rather than a mere endgame guard: the Hard difficulty (spec §7) caps depth so the bot
     * stays beatable by a human who plans a few moves ahead, instead of out-calculating everyone as
     * its budget allows — the budget then only bounds how long the capped search may take.
     *
     * @param maxDepth deepest iteration to run; must be {@code >= 1}.
     */
    public IterativeDeepeningSearch(GameRules<S, M> rules, Evaluator<S> evaluator,
                                    MoveOrdering<S, M> ordering, Duration budget, int maxDepth) {
        this(rules, evaluator, ordering, requirePositiveNanos(budget), null, maxDepth);
    }

    /**
     * Test seam: drives deepening off an explicit {@code outOfTime} predicate (and an explicit
     * {@code maxDepth}) instead of the wall clock, so iteration count is deterministic. With
     * {@code outOfTime} that never fires, this is a plain fixed-depth iterative search to
     * {@code maxDepth}.
     */
    IterativeDeepeningSearch(GameRules<S, M> rules, Evaluator<S> evaluator, MoveOrdering<S, M> ordering,
                             BooleanSupplier outOfTime, int maxDepth) {
        this(rules, evaluator, ordering, 0L, Objects.requireNonNull(outOfTime, "outOfTime"), maxDepth);
    }

    private IterativeDeepeningSearch(GameRules<S, M> rules, Evaluator<S> evaluator, MoveOrdering<S, M> ordering,
                                     long budgetNanos, BooleanSupplier injectedAbort, int maxDepth) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.ordering = Objects.requireNonNull(ordering, "ordering");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, was " + maxDepth);
        }
        this.budgetNanos = budgetNanos;
        this.injectedAbort = injectedAbort;
        this.maxDepth = maxDepth;
    }

    /** The wall-clock budget per move ({@link Duration#ZERO} for the predicate-driven test seam). */
    public Duration budget() {
        return Duration.ofNanos(budgetNanos);
    }

    /** The deepest iteration this search will run, however generous the budget. */
    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public M bestMove(S state) {
        return deepen(state).move();
    }

    /**
     * Deepens from {@code state} within the budget and reports both the chosen move and how deep the
     * last completed iteration reached. Holds no mutable search state across the call, so it is safe
     * to share one instance across concurrent games (each {@link AlphaBetaSearch} it spawns owns its
     * own transient counters).
     */
    public Progress<M> deepen(S state) {
        BooleanSupplier outOfTime = injectedAbort != null ? injectedAbort : deadlineFromNow();

        // Depth 1 is uninterruptible: it guarantees a legal move to return whatever the budget is.
        M best = new AlphaBetaSearch<>(rules, evaluator, ordering, 1).bestMove(state);
        int completed = 1;

        for (int depth = 2; depth <= maxDepth; depth++) {
            if (outOfTime.getAsBoolean()) {
                break; // no time even to start the next iteration
            }
            MoveOrdering<S, M> pvFirst = MoveOrdering.hintFirst(best, ordering);
            AlphaBetaSearch<S, M> search = new AlphaBetaSearch<>(rules, evaluator, pvFirst, outOfTime, depth);
            try {
                best = search.bestMove(state);
                completed = depth;
            } catch (AlphaBetaSearch.SearchAborted aborted) {
                break; // budget hit mid-iteration — keep the last fully completed depth's move
            }
        }
        return new Progress<>(best, completed);
    }

    private BooleanSupplier deadlineFromNow() {
        long deadline = System.nanoTime() + budgetNanos;
        return () -> System.nanoTime() >= deadline;
    }

    private static long requirePositiveNanos(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("budget must be positive, was " + budget);
        }
        return budget.toNanos();
    }
}
