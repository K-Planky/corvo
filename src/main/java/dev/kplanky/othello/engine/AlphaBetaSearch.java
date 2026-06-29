package dev.kplanky.othello.engine;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * <b>Alpha-beta</b> pruning on top of {@link NegamaxSearch} (spec §7, rung 3 of the AI ladder). It
 * computes the <em>same</em> value as full-width negamax but skips subtrees that provably cannot
 * affect the result: it carries a window {@code [alpha, beta]} — the best score the side to move is
 * already assured of ({@code alpha}) and the best the opponent will allow ({@code beta}) — and the
 * moment a node's score reaches {@code beta} the rest of its siblings are cut, because the parent
 * would never let play reach this node anyway.
 *
 * <p>This rung is verified two ways against rung 2 (spec §7's AI-engine tests): for any
 * position+depth it returns the <b>same best move</b> as {@link NegamaxSearch} (correctness — same
 * earliest-on-ties tie-break), while {@link #nodesEvaluated()} reports <b>strictly fewer</b> nodes
 * whenever the branching admits any pruning (the concrete, measurable win). Node counting matches
 * negamax exactly (one count per searched child, root excluded) so the "before vs. after" comparison
 * is apples-to-apples.
 *
 * <p>Like negamax the search stays game-agnostic — parameterized only by {@link GameRules} and
 * {@link Evaluator} (spec §6) — and traverses a tree-internal forced pass via
 * {@link GameRules#pass(Object)} (a non-terminal node with no legal move), flipping the window like
 * any other ply.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public final class AlphaBetaSearch<S, M> implements Search<S, M> {

    /**
     * Sentinel "negative infinity" window bound, mirroring {@link NegamaxSearch}. Deliberately
     * {@code -Integer.MAX_VALUE} rather than {@code Integer.MIN_VALUE} so that negating it (negamax
     * negates child values and swaps {@code [alpha, beta]} to {@code [-beta, -alpha]}) never
     * overflows: {@code -POS_INF == NEG_INF} and {@code -NEG_INF == POS_INF}.
     */
    private static final int NEG_INF = -Integer.MAX_VALUE;
    private static final int POS_INF = Integer.MAX_VALUE;

    /** Abort hook for a search that always runs to completion (the default for rungs 2–3). */
    private static final BooleanSupplier NEVER_ABORT = () -> false;

    private final GameRules<S, M> rules;
    private final Evaluator<S> evaluator;
    private final MoveOrdering<S, M> ordering;
    private final BooleanSupplier abort;
    private final int depth;

    private long nodes;

    /**
     * Thrown to unwind the recursion when {@code abort} fires mid-search (spec §7's iterative
     * deepening aborts a depth that overruns the time budget). It carries no stack trace — it is
     * control flow, not an error — and is caught by the caller that supplied the abort hook.
     */
    public static final class SearchAborted extends RuntimeException {
        public SearchAborted() {
            super(null, null, false, false);
        }
    }

    /**
     * Unordered alpha-beta — tries moves in {@link GameRules#getLegalMoves} order. Equivalent to
     * passing {@link MoveOrdering#none()}; this is the node-count baseline an ordered search beats.
     *
     * @param depth plies to search from the root; must be {@code >= 1}. Evaluator scores must stay
     *              within a range whose negation is representable (i.e. never {@code Integer.MIN_VALUE}).
     */
    public AlphaBetaSearch(GameRules<S, M> rules, Evaluator<S> evaluator, int depth) {
        this(rules, evaluator, MoveOrdering.none(), NEVER_ABORT, depth);
    }

    /**
     * Alpha-beta that tries moves in {@code ordering}'s preferred order at every node. A good
     * ordering (likely-good moves first) makes cutoffs happen sooner, so the search visits strictly
     * fewer nodes than the unordered baseline for the <em>same</em> position value (spec §7).
     *
     * @param depth plies to search from the root; must be {@code >= 1}. Evaluator scores must stay
     *              within a range whose negation is representable (i.e. never {@code Integer.MIN_VALUE}).
     */
    public AlphaBetaSearch(GameRules<S, M> rules, Evaluator<S> evaluator, MoveOrdering<S, M> ordering, int depth) {
        this(rules, evaluator, ordering, NEVER_ABORT, depth);
    }

    /**
     * Alpha-beta whose search can be interrupted: {@code abort} is polled at every node, and when it
     * returns {@code true} the search throws {@link SearchAborted} to unwind. Iterative deepening
     * (spec §7) uses this to stop a depth that overruns its time budget and fall back to the last
     * fully completed depth.
     *
     * @param depth plies to search from the root; must be {@code >= 1}. Evaluator scores must stay
     *              within a range whose negation is representable (i.e. never {@code Integer.MIN_VALUE}).
     */
    public AlphaBetaSearch(GameRules<S, M> rules, Evaluator<S> evaluator, MoveOrdering<S, M> ordering,
                           BooleanSupplier abort, int depth) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.ordering = Objects.requireNonNull(ordering, "ordering");
        this.abort = Objects.requireNonNull(abort, "abort");
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be >= 1, was " + depth);
        }
        this.depth = depth;
    }

    /** Nodes visited by the most recent {@link #bestMove} call — the post-pruning count for §7. */
    public long nodesEvaluated() {
        return nodes;
    }

    @Override
    public M bestMove(S state) {
        nodes = 0;
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("no legal move available — the caller must pass");
        }
        moves = ordering.order(state, moves);

        M best = null;
        int bestScore = NEG_INF;
        int alpha = NEG_INF; // the root has no parent, so beta stays +inf (no root-level cutoff)
        for (M move : moves) {
            // Child value is from the opponent's perspective; negate it and swap the window. The root
            // beta is +inf, so each improving child is searched with an open upper bound and returns
            // an exact score — which is why the best move matches full-width negamax exactly.
            int score = -search(rules.applyMove(state, move), depth - 1, -POS_INF, -alpha);
            if (best == null || score > bestScore) { // strict ">" keeps the earliest move on ties
                bestScore = score;
                best = move;
                alpha = score; // raise the bar; weaker-or-equal later moves get pruned, not chosen
            }
        }
        return best;
    }

    /**
     * Alpha-beta negamax value of {@code state} from its side-to-move's perspective, searched
     * {@code depth} plies within the window {@code [alpha, beta]}. Leaves (depth exhausted or a
     * terminal position) are scored directly by the evaluator.
     */
    private int search(S state, int depth, int alpha, int beta) {
        if (abort.getAsBoolean()) {
            throw new SearchAborted();
        }
        nodes++;
        if (depth == 0 || rules.isTerminal(state)) {
            return evaluator.evaluate(state, rules.currentPlayer(state));
        }
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            // Non-terminal with no move: a forced pass. It flips perspective like any move, so the
            // value is negated and the window swapped; it consumes a ply (a double pass terminates).
            return -search(rules.pass(state), depth - 1, -beta, -alpha);
        }
        moves = ordering.order(state, moves);
        int best = NEG_INF;
        for (M move : moves) {
            int score = -search(rules.applyMove(state, move), depth - 1, -beta, -alpha);
            if (score > best) {
                best = score;
            }
            if (best > alpha) {
                alpha = best;
            }
            if (alpha >= beta) {
                break; // beta cutoff: the parent would never allow play to reach this node
            }
        }
        return best;
    }
}
