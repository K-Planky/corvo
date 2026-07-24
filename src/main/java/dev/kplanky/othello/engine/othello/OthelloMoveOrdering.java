package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.MoveOrdering;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Static positional move ordering for Othello alpha-beta (spec §7's "corners → high-value squares").
 * Every board square carries a fixed preference weight and moves are tried highest-weight first, so
 * the search examines a corner before a quiet centre square and a centre square before an X-square
 * (the diagonal-from-corner trap), the order that makes alpha-beta cut earliest.
 *
 * <p>This is a deliberately <em>cheap, rough</em> hint kept separate from the real position
 * {@link dev.kplanky.othello.engine.Evaluator} (Milestone 6 rung 4): ordering only needs a fast
 * "probably good square" ranking, not an accurate score, and it never affects the value the search
 * computes, only how many nodes it visits to get there. The richer "previous iteration's best move
 * first" hint pairs with iterative deepening and lands with that rung; this rung supplies the static
 * table it builds on.
 *
 * <p>Weights are the classic Othello square table: corners highest (they can never be flipped),
 * the four diagonally-corner-adjacent <em>X-squares</em> lowest, and the orthogonally-adjacent
 * <em>C-squares</em> next-lowest (occupying either while its corner is empty tends to hand the corner
 * away). Equal-weight moves keep their incoming order (the sort is stable), so ordering stays
 * deterministic.
 */
public final class OthelloMoveOrdering implements MoveOrdering<OthelloState, OthelloMove> {

    /** Per-square preference, indexed {@code row*8+col} (a1 = 0). Higher = try earlier. */
    private static final int[] SQUARE_VALUE = {
            120, -20,  20,   5,   5,  20, -20, 120,
            -20, -40,  -5,  -5,  -5,  -5, -40, -20,
             20,  -5,  15,   3,   3,  15,  -5,  20,
              5,  -5,   3,   3,   3,   3,  -5,   5,
              5,  -5,   3,   3,   3,   3,  -5,   5,
             20,  -5,  15,   3,   3,  15,  -5,  20,
            -20, -40,  -5,  -5,  -5,  -5, -40, -20,
            120, -20,  20,   5,   5,  20, -20, 120,
    };

    private static final Comparator<OthelloMove> BY_SQUARE_VALUE_DESC =
            Comparator.comparingInt((OthelloMove move) -> SQUARE_VALUE[move.square()]).reversed();

    @Override
    public List<OthelloMove> order(OthelloState state, List<OthelloMove> moves) {
        if (moves.size() < 2) {
            return moves; // nothing to reorder
        }
        List<OthelloMove> ordered = new ArrayList<>(moves);
        ordered.sort(BY_SQUARE_VALUE_DESC); // stable: equal-weight moves keep their input order
        return ordered;
    }
}
