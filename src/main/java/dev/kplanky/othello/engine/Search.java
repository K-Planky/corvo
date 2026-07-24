package dev.kplanky.othello.engine;

/**
 * Game-agnostic move-selection seam (spec §6/§7). The real-time game loop (Milestone 4) and the AI
 * ladder (Milestone 6) depend on this interface, not on a concrete algorithm: an implementation is
 * built from a {@link GameRules} and an {@link Evaluator}, so the search stays reusable across
 * games. Milestone 6 supplies the negamax → alpha-beta → iterative-deepening implementations behind
 * it; {@link GreedySearch} is the minimal reference implementation that proves the seam.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public interface Search<S, M> {

    /**
     * The move the search prefers in {@code state}, chosen from {@link GameRules#getLegalMoves}.
     *
     * <p>Called only when at least one legal move exists; a forced pass (empty legal-move list) is
     * handled by the caller, the human submits it explicitly and the server auto-passes for the
     * bot (spec §6/§9), not by the search.
     */
    M bestMove(S state);
}
