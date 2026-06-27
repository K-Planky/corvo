package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OthelloRulesLegalMovesTest {

    private final OthelloRules rules = new OthelloRules();

    private List<Integer> legalSquares(OthelloState state) {
        return rules.getLegalMoves(state).stream().map(OthelloMove::square).sorted().toList();
    }

    @Test
    void blackOpeningHasTheFourKnownMoves() {
        // The classic opening: Black may play c4, d3, e6, f5.
        // c4 = row3 col2 = 26, d3 = row2 col3 = 19, e6 = row5 col4 = 44, f5 = row4 col5 = 37.
        assertThat(legalSquares(OthelloState.initial())).containsExactly(19, 26, 37, 44);
    }

    @Test
    void whiteToMoveFromStartHasTheMirroredFourMoves() {
        // Same centre position but White to move (180°-symmetric to Black's opening):
        // e3 = 20, f4 = 29, c5 = 34, d6 = 43.
        long black = (1L << 28) | (1L << 35);
        long white = (1L << 27) | (1L << 36);
        OthelloState whiteToMove = new OthelloState(black, white, Player.WHITE, 0);

        assertThat(legalSquares(whiteToMove)).containsExactly(20, 29, 34, 43);
    }

    @Test
    void runOffTheRightEdgeDoesNotWrapToTheNextRow() {
        // Row 1 (rank 1): b1=White, c1=Black  → Black plays a1, capturing b1 (a real move).
        // Also f1=Black, g1=White, h1=White, a2 empty. A naive east shift would walk the g1/h1
        // run "off" the right edge and report a2 (square 8) as a phantom legal move. With edge
        // masking it must not — the only legal move is a1 (square 0).
        long black = (1L << 2) | (1L << 5);            // c1, f1
        long white = (1L << 1) | (1L << 6) | (1L << 7); // b1, g1, h1
        OthelloState state = new OthelloState(black, white, Player.BLACK, 0);

        assertThat(legalSquares(state)).containsExactly(0);
        assertThat(OthelloRules.legalMoveMask(state) & (1L << 8))
                .as("a2 (square 8) must not appear via right-edge wraparound")
                .isZero();
    }

    @Test
    void captureStartingAtTheLeftEdgeWorks() {
        // Positive sanity check that bracketing works for a disc sitting on the a-file:
        // a1=Black, b1=White, c1=White, d1 empty → Black plays d1, capturing b1,c1.
        // (The decisive no-wrap proof is the right-edge test above; here a1>>>1 is simply 0, so
        // masked and naive generation agree — this only confirms an edge-anchored capture is found.)
        // Only legal move is d1 (square 3).
        long black = 1L << 0;                          // a1
        long white = (1L << 1) | (1L << 2);            // b1, c1
        OthelloState state = new OthelloState(black, white, Player.BLACK, 0);

        assertThat(legalSquares(state)).containsExactly(3);
    }

    @Test
    void positionWithNoBracketsHasNoLegalMoves() {
        // Two adjacent same-colour discs and nothing to bracket → no legal moves (an empty list;
        // the pass rule that interprets this lands in M1.4).
        long black = (1L << 0) | (1L << 1); // a1, b1, both black, no opponent discs
        OthelloState state = new OthelloState(black, 0L, Player.BLACK, 0);

        assertThat(rules.getLegalMoves(state)).isEmpty();
    }

    @Test
    void legalMovesAreReturnedInAscendingSquareOrder() {
        // getLegalMoves should be deterministic (ascending square index) for stable tests/clients.
        List<OthelloMove> moves = rules.getLegalMoves(OthelloState.initial());
        assertThat(moves).extracting(OthelloMove::square).isSorted();
    }
}
