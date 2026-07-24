package dev.kplanky.othello.engine;

import java.util.List;
import java.util.Objects;

/**
 * Full-width <b>negamax</b> search to a fixed depth (spec §7, rung 2 of the AI ladder). Negamax is
 * the single-function form of minimax for a two-player zero-sum game: a node's value is scored from
 * the side-to-move's perspective, and each parent negates the child's value
 * ({@code value(parent) = max over moves of -value(child)}). This exploits the
 * {@link Evaluator}'s zero-sum convention ("the same position scored from the opponent's perspective
 * is the negation"), so one branch replaces the separate min and max branches of textbook minimax.
 *
 * <p>This rung does <em>no</em> pruning, it visits every node to a fixed depth. It exists as the
 * <b>correctness reference</b> for alpha-beta (rung 3): for any position and depth, alpha-beta must
 * return the same best move while visiting strictly fewer nodes. {@link #nodesEvaluated()} exposes
 * the per-search node count so that "before vs. after" pruning win can be measured.
 *
 * <p>The search stays game-agnostic, it is parameterized only by {@link GameRules} and
 * {@link Evaluator} (spec §6). A forced pass inside the tree (a non-terminal node with no legal
 * move) is advanced via {@link GameRules#pass(Object)}: the search cannot construct a pass move
 * {@code M} itself, since {@code getLegalMoves} returns an empty list for one.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public final class NegamaxSearch<S, M> implements Search<S, M> {

    /**
     * Sentinel "negative infinity" seed for the per-node maximisation. Deliberately
     * {@code -Integer.MAX_VALUE} rather than {@code Integer.MIN_VALUE} so that negating it (negamax
     * negates child values) does not overflow. It is only ever a seed: every searched node has at
     * least one continuation (a legal move or a forced pass), so a real score always replaces it.
     */
    private static final int NEG_INF = -Integer.MAX_VALUE;

    private final GameRules<S, M> rules;
    private final Evaluator<S> evaluator;
    private final int depth;

    private long nodes;

    /**
     * @param depth plies to search from the root; must be {@code >= 1}. Evaluator scores must stay
     *              within a range whose negation is representable (i.e. never {@code Integer.MIN_VALUE}).
     */
    public NegamaxSearch(GameRules<S, M> rules, Evaluator<S> evaluator, int depth) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be >= 1, was " + depth);
        }
        this.depth = depth;
    }

    /** Nodes visited by the most recent {@link #bestMove} call (the pruning baseline for §7). */
    public long nodesEvaluated() {
        return nodes;
    }

    @Override
    public M bestMove(S state) {
        nodes = 0;
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("no legal move available, the caller must pass");
        }

        M best = null;
        int bestScore = NEG_INF;
        for (M move : moves) {
            // Child value is from the opponent's perspective; negate to score it for the mover.
            int score = -search(rules.applyMove(state, move), depth - 1);
            if (best == null || score > bestScore) { // strict ">" keeps the earliest move on ties
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    /**
     * Negamax value of {@code state} from its side-to-move's perspective, searched {@code depth}
     * plies. Leaves (depth exhausted or a terminal position) are scored directly by the evaluator.
     */
    private int search(S state, int depth) {
        nodes++;
        if (depth == 0 || rules.isTerminal(state)) {
            return evaluator.evaluate(state, rules.currentPlayer(state));
        }
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            // Non-terminal with no move: a forced pass. It flips perspective like any move, so it is
            // negated and consumes a ply (a consecutive double pass then ends the game, terminating).
            return -search(rules.pass(state), depth - 1);
        }
        int best = NEG_INF;
        for (M move : moves) {
            best = Math.max(best, -search(rules.applyMove(state, move), depth - 1));
        }
        return best;
    }
}
