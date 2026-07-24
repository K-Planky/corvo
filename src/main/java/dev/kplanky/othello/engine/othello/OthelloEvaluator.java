package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Evaluator;
import dev.kplanky.othello.engine.Player;

/**
 * Phase-aware Othello position evaluation (spec §7, M6 rung 4, "the centerpiece"). A weighted sum
 * of features scored from a {@code perspective}'s point of view, with the weights of two of them
 * <b>shifting by game phase</b> (how full the board is):
 *
 * <ul>
 *   <li><b>Corner occupancy</b>, a large, near-constant bonus per corner held. Corners can never be
 *       flipped, so owning one is permanent territory.</li>
 *   <li><b>Corner-adjacency penalty</b>, occupying a square next to an <em>empty</em> corner is
 *       dangerous because it tends to hand the corner to the opponent. The diagonal
 *       <em>X-squares</em> are the worst; the orthogonal <em>C-squares</em> are bad but less so. The
 *       penalty applies only while the corner is still empty, once the corner is settled the
 *       adjacent squares are ordinary.</li>
 *   <li><b>Mobility</b>, how many legal moves a side has. It dominates the opening/midgame:
 *       restricting the opponent's options is how you steer the game. Its weight <em>fades</em> as
 *       the board fills.</li>
 *   <li><b>Disc parity</b>, the raw disc-count difference. Early leads routinely flip, so it barely
 *       matters in the opening; in the endgame it is what actually decides the result, so its weight
 *       <em>grows</em> toward the full board.</li>
 * </ul>
 *
 * <p>The interview point is exactly this trade: <em>mobility dominates the opening, disc count
 * dominates the endgame</em>, and the weights interpolate linearly between the two by disc count.
 *
 * <p>The score is built entirely from integer features and phase weights that depend only on the
 * board's fill (not on {@code perspective}), so it is exactly antisymmetric,
 * {@code evaluate(s, BLACK) == -evaluate(s, WHITE)}, the zero-sum convention the negamax/alpha-beta
 * search relies on. There is no special terminal branch: a finished board is simply the extreme of
 * the endgame phase, where the dominant parity weight already scores a win high and a loss low.
 */
public final class OthelloEvaluator implements Evaluator<OthelloState> {

    /** Per-corner bonus (also counted in parity, so a corner is worth corner + parity weight). */
    private static final int CORNER_WEIGHT = 80;
    /** Penalty for sitting on an X-square (diagonal to an empty corner), the most dangerous. */
    private static final int X_SQUARE_PENALTY = 24;
    /** Penalty for sitting on a C-square (orthogonal to an empty corner), bad, but less so. */
    private static final int C_SQUARE_PENALTY = 8;

    // Phase-shifting weights, linearly interpolated by disc count from opening (4 discs) to a full
    // board (64). Mobility starts high and fades; parity starts negligible and grows to dominate.
    private static final int MOBILITY_WEIGHT_OPENING = 15;
    private static final int MOBILITY_WEIGHT_ENDGAME = 1;
    private static final int PARITY_WEIGHT_OPENING = 1;
    private static final int PARITY_WEIGHT_ENDGAME = 20;

    private static final int OPENING_DISCS = 4;   // the four centre discs of the initial position
    private static final int FULL_BOARD_DISCS = 64;
    private static final int PHASE_SPAN = FULL_BOARD_DISCS - OPENING_DISCS; // 60

    /** The four corner squares (a1, h1, a8, h8), indexed {@code row*8+col}. */
    private static final int[] CORNER = {0, 7, 56, 63};
    /** The four corners as a single mask, derived from {@link #CORNER} so there is one source of truth. */
    private static final long CORNER_MASK = maskOf(CORNER);
    /** The X-square (diagonal neighbour) of {@link #CORNER}[i]: b2, g2, b7, g7. */
    private static final int[] X_SQUARE = {9, 14, 49, 54};
    /** The two C-squares (orthogonal neighbours) of {@link #CORNER}[i], as a mask. */
    private static final long[] C_SQUARES = {
            sq(1) | sq(8),    // b1, a2  → a1
            sq(6) | sq(15),   // g1, h2  → h1
            sq(48) | sq(57),  // a7, b8  → a8
            sq(55) | sq(62),  // h7, g8  → h8
    };

    private static long sq(int square) {
        return 1L << square;
    }

    @Override
    public int evaluate(OthelloState state, Player perspective) {
        Player opponent = perspective.opponent();
        long mine = state.discs(perspective);
        long theirs = state.discs(opponent);
        int filled = Long.bitCount(state.occupied());

        int cornerNet = bitCountDiff(mine & CORNER_MASK, theirs & CORNER_MASK);
        int adjacencyPenalty = adjacencyPenalty(state, mine) - adjacencyPenalty(state, theirs);

        long empty = ~state.occupied();
        int mobilityNet = Long.bitCount(OthelloRules.legalMoveMask(mine, theirs, empty))
                - Long.bitCount(OthelloRules.legalMoveMask(theirs, mine, empty));
        int parityNet = Long.bitCount(mine) - Long.bitCount(theirs);

        return CORNER_WEIGHT * cornerNet
                - adjacencyPenalty
                + mobilityWeight(filled) * mobilityNet
                + parityWeight(filled) * parityNet;
    }

    /** Weighted penalty for {@code discs} sitting next to a still-empty corner. */
    private static int adjacencyPenalty(OthelloState state, long discs) {
        long empty = ~state.occupied();
        int penalty = 0;
        for (int i = 0; i < CORNER.length; i++) {
            if ((empty & sq(CORNER[i])) == 0L) {
                continue; // corner already settled, the adjacent squares are no longer dangerous
            }
            if ((discs & sq(X_SQUARE[i])) != 0L) {
                penalty += X_SQUARE_PENALTY;
            }
            penalty += Long.bitCount(discs & C_SQUARES[i]) * C_SQUARE_PENALTY;
        }
        return penalty;
    }

    /** Mobility weight at the given disc count: high in the opening, fading toward the endgame. */
    static int mobilityWeight(int filled) {
        return lerpByPhase(MOBILITY_WEIGHT_OPENING, MOBILITY_WEIGHT_ENDGAME, filled);
    }

    /** Parity weight at the given disc count: negligible in the opening, dominant in the endgame. */
    static int parityWeight(int filled) {
        return lerpByPhase(PARITY_WEIGHT_OPENING, PARITY_WEIGHT_ENDGAME, filled);
    }

    /**
     * Linear interpolation from {@code opening} (at {@value #OPENING_DISCS} discs) to {@code endgame}
     * (at {@value #FULL_BOARD_DISCS} discs), clamped outside that range. Integer arithmetic only, so
     * the result depends solely on {@code filled}, never on which side is the perspective, which is
     * what keeps {@link #evaluate} exactly antisymmetric.
     */
    private static int lerpByPhase(int opening, int endgame, int filled) {
        int progress = Math.max(0, Math.min(PHASE_SPAN, filled - OPENING_DISCS));
        return opening + (endgame - opening) * progress / PHASE_SPAN;
    }

    private static long maskOf(int[] squares) {
        long mask = 0L;
        for (int square : squares) {
            mask |= sq(square);
        }
        return mask;
    }

    private static int bitCountDiff(long a, long b) {
        return Long.bitCount(a) - Long.bitCount(b);
    }
}
