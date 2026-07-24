package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;

import java.util.Optional;

/**
 * Immutable Othello position (spec §5/§6).
 *
 * <p>The board is two 64-bit bitboards, one bit per square, indexed as {@code row * 8 + col} with
 * {@code a1 = 0} (file a → col 0, rank 1 → row 0). A set bit in {@code black}/{@code white} means
 * that side occupies the square. The two bitboards are always disjoint (a square holds at most one
 * disc).
 *
 * <p>{@code consecutivePasses} tracks how many turns in a row have been passed; two in a row ends
 * the game (terminal detection arrives in a later task, it is carried in the state now so the
 * state model does not have to change).
 */
public record OthelloState(long black, long white, Player toMove, int consecutivePasses) {

    /** Initial bit indices for the four centre discs (spec §6). */
    private static final int D4 = 3 * 8 + 3; // 27, White
    private static final int E4 = 3 * 8 + 4; // 28, Black
    private static final int D5 = 4 * 8 + 3; // 35, Black
    private static final int E5 = 4 * 8 + 4; // 36, White

    public OthelloState {
        if ((black & white) != 0L) {
            throw new IllegalArgumentException("a square cannot hold both a black and a white disc");
        }
        if (consecutivePasses < 0) {
            throw new IllegalArgumentException("consecutivePasses cannot be negative");
        }
    }

    /**
     * The standard starting position: {@code d4=W, e4=B, d5=B, e5=W}, Black to move.
     */
    public static OthelloState initial() {
        long black = bit(E4) | bit(D5);
        long white = bit(D4) | bit(E5);
        return new OthelloState(black, white, Player.BLACK, 0);
    }

    /** The bitboard of squares occupied by either side. */
    public long occupied() {
        return black | white;
    }

    /** The bitboard belonging to {@code player}. */
    public long discs(Player player) {
        return player == Player.BLACK ? black : white;
    }

    /** The owner of {@code square} (0..63), or empty if the square is vacant. */
    public Optional<Player> at(int square) {
        long mask = bit(square);
        if ((black & mask) != 0L) {
            return Optional.of(Player.BLACK);
        }
        if ((white & mask) != 0L) {
            return Optional.of(Player.WHITE);
        }
        return Optional.empty();
    }

    /** Disc count for {@code player}. */
    public int count(Player player) {
        return Long.bitCount(discs(player));
    }

    /** A single-bit mask for square index {@code square} (0..63). */
    public static long bit(int square) {
        if (square < 0 || square > 63) {
            throw new IllegalArgumentException("square out of range: " + square);
        }
        return 1L << square;
    }
}
