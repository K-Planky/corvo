package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Disc-flipping correctness for placements (spec §6, M1.3). Squares are {@code row*8+col}, a1=0.
 *
 * <p>Each single-direction case puts a Black disc, a contiguous run of White discs, and an empty
 * landing square in a line, then plays Black on that square and asserts the whole White run flips.
 * The eight cases between them exercise every direction; later tests cover multi-direction flips,
 * multi-disc runs, the "must be bracketed" rule, and edge no-wrap.
 */
class OthelloRulesFlipTest {

    private final OthelloRules rules = new OthelloRules();

    private static long bit(int square) {
        return 1L << square;
    }

    /** Builds a Black-to-move state from raw black/white bitboards. */
    private static OthelloState blackToMove(long black, long white) {
        return new OthelloState(black, white, Player.BLACK, 0);
    }

    // ----- one flip per direction -------------------------------------------------------------

    @Test
    void flipEast() {
        // a4 empty (land), b4 c4 White, d4 Black → Black plays a4, walks east over b4,c4 to d4.
        int land = 3 * 8 + 0;   // a4 = 24
        long white = bit(3 * 8 + 1) | bit(3 * 8 + 2); // b4, c4
        long black = bit(3 * 8 + 3);                   // d4
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipWest() {
        // d4 land, b4 c4 White, a4 Black → Black plays d4, walks west over c4,b4 to a4.
        int land = 3 * 8 + 3;   // d4 = 27
        long white = bit(3 * 8 + 1) | bit(3 * 8 + 2); // b4, c4
        long black = bit(3 * 8 + 0);                   // a4
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipNorth() {
        // a1 land, a2 a3 White, a4 Black → Black plays a1, walks north over a2,a3 to a4.
        int land = 0 * 8 + 0;   // a1 = 0
        long white = bit(1 * 8 + 0) | bit(2 * 8 + 0); // a2, a3
        long black = bit(3 * 8 + 0);                   // a4
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipSouth() {
        // a4 land, a2 a3 White, a1 Black → Black plays a4, walks south over a3,a2 to a1.
        int land = 3 * 8 + 0;   // a4 = 24
        long white = bit(1 * 8 + 0) | bit(2 * 8 + 0); // a2, a3
        long black = bit(0 * 8 + 0);                   // a1
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipNortheast() {
        // a1 land, b2 c3 White, d4 Black → Black plays a1, walks NE over b2,c3 to d4.
        int land = 0 * 8 + 0;   // a1 = 0
        long white = bit(1 * 8 + 1) | bit(2 * 8 + 2); // b2, c3
        long black = bit(3 * 8 + 3);                   // d4
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipNorthwest() {
        // d1 land, c2 b3 White, a4 Black → Black plays d1, walks NW over c2,b3 to a4.
        int land = 0 * 8 + 3;   // d1 = 3
        long white = bit(1 * 8 + 2) | bit(2 * 8 + 1); // c2, b3
        long black = bit(3 * 8 + 0);                   // a4
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipSoutheast() {
        // a4 land, b3 c2 White, d1 Black → Black plays a4, walks SE over b3,c2 to d1.
        int land = 3 * 8 + 0;   // a4 = 24
        long white = bit(2 * 8 + 1) | bit(1 * 8 + 2); // b3, c2
        long black = bit(0 * 8 + 3);                   // d1
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void flipSouthwest() {
        // d4 land, c3 b2 White, a1 Black → Black plays d4, walks SW over c3,b2 to a1.
        int land = 3 * 8 + 3;   // d4 = 27
        long white = bit(2 * 8 + 2) | bit(1 * 8 + 1); // c3, b2
        long black = bit(0 * 8 + 0);                   // a1
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    // ----- multi-direction and multi-disc ------------------------------------------------------

    @Test
    void singleMoveFlipsInMultipleDirectionsAtOnce() {
        // Land at d4 (27). Black brackets White runs going west (a4 anchor) and north (d6 anchor):
        //   west:  a4 Black, b4 c4 White  → flips b4,c4
        //   north: d4..: d5 d6? use d5 White, d6 Black → flips d5 (north walk)
        int land = 3 * 8 + 3; // d4 = 27
        long whiteWest = bit(3 * 8 + 1) | bit(3 * 8 + 2); // b4, c4
        long whiteNorth = bit(4 * 8 + 3);                 // d5
        long blackAnchors = bit(3 * 8 + 0) | bit(5 * 8 + 3); // a4, d6
        OthelloState state = blackToMove(blackAnchors, whiteWest | whiteNorth);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(whiteWest | whiteNorth);
    }

    @Test
    void flipsAcrossALongMultiDiscRun() {
        // a1 land, b1..g1 all White (six discs), h1 Black → playing a1 flips the whole six-disc run.
        int land = 0; // a1
        long white = 0L;
        for (int col = 1; col <= 6; col++) {
            white |= bit(col); // b1..g1
        }
        long black = bit(7); // h1
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isEqualTo(white);
    }

    @Test
    void unbracketedRunDoesNotFlip() {
        // a1 land, b1 c1 White, d1 empty (no Black anchor) → nothing is bracketed, nothing flips.
        int land = 0;
        long white = bit(1) | bit(2); // b1, c1
        OthelloState state = blackToMove(0L, white);

        assertThat(OthelloRules.flips(state, land)).isZero();
    }

    @Test
    void gapInTheRunBreaksTheBracket() {
        // a1 land, b1 White, c1 empty, d1 White, e1 Black → the run is not contiguous, so no flip.
        int land = 0;
        long white = bit(1) | bit(3); // b1, d1 (c1 empty)
        long black = bit(4);          // e1
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, land)).isZero();
    }

    // ----- edge no-wrap ------------------------------------------------------------------------

    @Test
    void westWalkDoesNotWrapOffTheLeftEdge() {
        // Row 1: b1..h1 White, a1 Black. Land at a2 (square 8). A naive west shift of a2 (>>>1)
        // lands on h1, so an unmasked west-walk would wrap into row 1 and bracket b1..h1 against
        // the a1 Black anchor, flipping the whole row. With NOT_H_FILE masking, west(a2) = 0, so
        // nothing flips.
        long white = 0L;
        for (int col = 1; col <= 7; col++) {
            white |= bit(col); // b1..h1
        }
        long black = bit(0); // a1
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, 8)).isZero(); // a2 = 8
    }

    @Test
    void eastWalkDoesNotWrapOffTheRightEdge() {
        // Row 2: a2..g2 White, h2 Black. Land at h1 (square 7). A naive east shift of h1 (<<1)
        // lands on a2, so an unmasked east-walk would wrap into row 2 and bracket a2..g2 against
        // the h2 Black anchor. With NOT_A_FILE masking, east(h1) = 0, so nothing flips.
        long white = 0L;
        for (int col = 0; col <= 6; col++) {
            white |= bit(8 + col); // a2..g2
        }
        long black = bit(8 + 7); // h2 = 15
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, 7)).isZero(); // h1 = 7
    }

    @Test
    void diagonalWalkDoesNotWrapOffTheEdge() {
        // a3 b4 White on the NE diagonal, c5 Black. Land at h1 (square 7). A naive NE shift of h1
        // (<<9) lands on a3 (square 16), so an unmasked NE-walk would wrap off the right edge into
        // the a3..b4 run and bracket it against c5. With NOT_A_FILE masking, northeast(h1) = 0, so
        // nothing flips, closing the no-wrap claim on a diagonal direction too.
        long white = bit(2 * 8 + 0) | bit(3 * 8 + 1); // a3, b4
        long black = bit(4 * 8 + 2);                   // c5
        OthelloState state = blackToMove(black, white);

        assertThat(OthelloRules.flips(state, 7)).isZero(); // h1 = 7
    }

    // ----- applyMove integration ---------------------------------------------------------------

    @Test
    void applyMoveFlipsDiscsAdvancesTurnAndConserves() {
        // Black opens with d3 (square 19) from the initial position, flipping the White d4 (27).
        OthelloState next = rules.applyMove(OthelloState.initial(), OthelloMove.at(19));

        assertThat(next.at(19)).contains(Player.BLACK);     // placed disc
        assertThat(next.at(27)).contains(Player.BLACK);     // flipped from White
        assertThat(next.toMove()).isEqualTo(Player.WHITE);  // turn advanced
        assertThat(next.consecutivePasses()).isZero();      // a placement resets the pass counter
        assertThat(next.count(Player.BLACK)).isEqualTo(4);  // 2 + placed + 1 flipped
        assertThat(next.count(Player.WHITE)).isEqualTo(1);  // 2 - 1 flipped
        // The two boards stay disjoint and total disc count grew by exactly one (the placement).
        assertThat(next.black() & next.white()).isZero();
        assertThat(Long.bitCount(next.occupied())).isEqualTo(5);
    }

    @Test
    void applyMoveRejectsAMoveThatFlipsNothing() {
        // a1 is empty in the initial position but brackets nothing → illegal placement.
        assertThatThrownBy(() -> rules.applyMove(OthelloState.initial(), OthelloMove.at(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyMoveRejectsAnOccupiedSquare() {
        // d4 (27) is occupied by White in the initial position.
        assertThatThrownBy(() -> rules.applyMove(OthelloState.initial(), OthelloMove.at(27)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
