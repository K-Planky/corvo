package dev.kplanky.othello.engine.othello;

import java.util.function.LongUnaryOperator;

/**
 * Bitboard direction shifts for the {@code row*8+col}, {@code a1=0} layout (spec §5/§6).
 *
 * <p>Each shift moves every disc one square in a compass direction. Horizontal and diagonal shifts
 * mask out the file that would otherwise wrap across the board edge: shifting "east" (col+1) by one
 * bit turns an h-file disc into the a-file of the next row, so the result is masked with
 * {@link #NOT_A_FILE}; westward shifts are masked with {@link #NOT_H_FILE}. Vertical shifts cannot
 * wrap — bits shifted past the ends of the 64-bit board simply disappear. Masking out this
 * wraparound is the load-bearing edge correctness the spec calls out.
 */
final class Bitboards {

    private Bitboards() {
    }

    /** All squares except the a-file (col 0). */
    static final long NOT_A_FILE = 0xFEFEFEFEFEFEFEFEL;

    /** All squares except the h-file (col 7). */
    static final long NOT_H_FILE = 0x7F7F7F7F7F7F7F7FL;

    static long east(long b) {
        return (b << 1) & NOT_A_FILE;
    }

    static long west(long b) {
        return (b >>> 1) & NOT_H_FILE;
    }

    static long north(long b) {
        return b << 8;
    }

    static long south(long b) {
        return b >>> 8;
    }

    static long northeast(long b) {
        return (b << 9) & NOT_A_FILE;
    }

    static long northwest(long b) {
        return (b << 7) & NOT_H_FILE;
    }

    static long southeast(long b) {
        return (b >>> 7) & NOT_A_FILE;
    }

    static long southwest(long b) {
        return (b >>> 9) & NOT_H_FILE;
    }

    /** All eight directions, for iterating move generation and flipping uniformly. */
    static final LongUnaryOperator[] DIRECTIONS = {
        Bitboards::east,
        Bitboards::west,
        Bitboards::north,
        Bitboards::south,
        Bitboards::northeast,
        Bitboards::northwest,
        Bitboards::southeast,
        Bitboards::southwest,
    };
}
