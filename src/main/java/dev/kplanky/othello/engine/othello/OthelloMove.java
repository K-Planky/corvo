package dev.kplanky.othello.engine.othello;

/**
 * An Othello move: either placing a disc on a square (0..63, indexed {@code row*8+col}) or a pass.
 *
 * <p>A pass is represented by {@link #PASS_SQUARE} rather than a separate type so the move stays a
 * cheap value. Pass semantics (a player may pass only with zero legal moves) are enforced by the
 * rules engine, not by this type, see spec §6/§14.
 */
public record OthelloMove(int square) {

    /** Sentinel square value denoting a pass. */
    public static final int PASS_SQUARE = -1;

    public OthelloMove {
        if (square != PASS_SQUARE && (square < 0 || square > 63)) {
            throw new IllegalArgumentException("square out of range: " + square);
        }
    }

    /** A disc placement on {@code square} (0..63). */
    public static OthelloMove at(int square) {
        if (square < 0 || square > 63) {
            throw new IllegalArgumentException("square out of range: " + square);
        }
        return new OthelloMove(square);
    }

    /** A pass. */
    public static OthelloMove pass() {
        return new OthelloMove(PASS_SQUARE);
    }

    public boolean isPass() {
        return square == PASS_SQUARE;
    }
}
