package dev.kplanky.othello.engine;

import java.util.List;
import java.util.Objects;

/**
 * Minimal reference {@link Search}: a one-ply greedy that plays the legal move whose resulting
 * position the {@link Evaluator} scores highest for the side to move. It exists to prove the generic
 * seam is genuinely <em>wired</em> — it exercises every collaborator
 * ({@link GameRules#currentPlayer}, {@link GameRules#getLegalMoves}, {@link GameRules#applyMove},
 * {@link Evaluator#evaluate}) and compiles against any stub evaluator.
 *
 * <p>It is deliberately <em>not</em> a rung of the Milestone-6 AI ladder
 * (random → negamax → alpha-beta → iterative deepening); those deeper searches implement
 * {@link Search} alongside this one and reuse the same {@code GameRules} + {@code Evaluator} seam.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public final class GreedySearch<S, M> implements Search<S, M> {

    private final GameRules<S, M> rules;
    private final Evaluator<S> evaluator;

    public GreedySearch(GameRules<S, M> rules, Evaluator<S> evaluator) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public M bestMove(S state) {
        Player perspective = rules.currentPlayer(state);
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("no legal move available — the caller must pass");
        }

        // Seed from the first legal move (not Integer.MIN_VALUE) so the method is total even if an
        // evaluator returns Integer.MIN_VALUE for every move; ties keep the earliest move.
        M best = moves.get(0);
        int bestScore = evaluator.evaluate(rules.applyMove(state, best), perspective);
        for (M move : moves.subList(1, moves.size())) {
            int score = evaluator.evaluate(rules.applyMove(state, move), perspective);
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }
}
