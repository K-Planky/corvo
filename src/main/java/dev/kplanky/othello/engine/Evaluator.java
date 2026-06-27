package dev.kplanky.othello.engine;

/**
 * Game-agnostic position evaluator (spec §6/§7). Scoring a position is game-specific <em>tuning</em>,
 * not a rule, so it lives on its own strategy interface rather than on {@link GameRules}: the
 * generic search is parameterized by <em>both</em> a {@code GameRules<S, M>} and an
 * {@code Evaluator<S>}. That separation is what keeps the "add a second game" claim honest — a new
 * game supplies its own rules <em>and</em> evaluator, and the search code is reused unchanged.
 *
 * <p>The real Othello evaluation (corner occupancy, X/C-square penalties, mobility, disc parity,
 * weights shifting by game phase) lands in Milestone 6; this seam only fixes the contract.
 *
 * @param <S> the game state type
 */
@FunctionalInterface
public interface Evaluator<S> {

    /**
     * Heuristic score of {@code state} from {@code perspective}'s point of view: higher is better
     * for {@code perspective}. By the zero-sum convention the same position scored from the
     * opponent's perspective is the negation — the property the negamax search in Milestone 6
     * relies on.
     */
    int evaluate(S state, Player perspective);
}
