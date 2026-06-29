package dev.kplanky.othello.engine;

import java.util.List;

/**
 * Game-agnostic move-ordering strategy for alpha-beta search (spec §7). Alpha-beta prunes more when
 * likely-good moves are tried <em>first</em> (a cutoff found early skips the rest of a node's
 * siblings), so the search asks this seam to reorder a node's legal moves before iterating them.
 * Ordering only changes the <em>order</em> of the same legal moves — never which moves exist — so it
 * is purely a performance hint: it cannot change a position's value, only how many nodes are visited
 * to prove it.
 *
 * <p>Like {@link Evaluator}, ordering is game-specific <em>tuning</em> (Othello favours corners and
 * shuns the squares next to an empty corner), so it lives on its own strategy rather than on
 * {@link GameRules}. The {@link #none()} identity ordering preserves a search's unordered behaviour
 * exactly — the baseline the ordered search must beat on node count.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
@FunctionalInterface
public interface MoveOrdering<S, M> {

    /**
     * Returns {@code moves} reordered best-first for {@code state}. Must return exactly the same
     * moves (a permutation), never adding, dropping, or altering one. Implementations must not
     * mutate the input list; return a new list (or the same instance when ordering is a no-op).
     */
    List<M> order(S state, List<M> moves);

    /** The identity ordering — leaves moves in {@link GameRules#getLegalMoves} order (no pruning aid). */
    static <S, M> MoveOrdering<S, M> none() {
        return (state, moves) -> moves;
    }
}
