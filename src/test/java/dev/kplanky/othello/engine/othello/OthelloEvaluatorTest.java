package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.AlphaBetaSearch;
import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-aware evaluation (spec §7, M6 rung 4). Proves the four behaviours the Done-when names:
 * corner-heavy positions score high; sitting next to an <em>empty</em> corner is penalised (and the
 * penalty lifts once the corner is settled); and the mobility vs. parity weights shift from
 * opening to endgame. Also pins the zero-sum antisymmetry the search relies on and a search-wiring
 * sanity check.
 */
class OthelloEvaluatorTest {

    private final OthelloEvaluator evaluator = new OthelloEvaluator();
    private final OthelloRules rules = new OthelloRules();

    private static long sq(int square) {
        return 1L << square;
    }

    // --- Corner occupancy scores high -----------------------------------------------------------

    @Test
    void owningACornerScoresHigherThanAQuietSquare() {
        // Same material (one disc each), Black's disc is the only difference: a1 corner vs the quiet
        // d3 square. The corner version must score strictly higher, and positive, for Black.
        OthelloState withCorner = new OthelloState(sq(0), sq(27), Player.BLACK, 0);   // Black a1, White d4
        OthelloState withoutCorner = new OthelloState(sq(19), sq(27), Player.BLACK, 0); // Black d3, White d4

        assertThat(evaluator.evaluate(withCorner, Player.BLACK))
                .isGreaterThan(evaluator.evaluate(withoutCorner, Player.BLACK))
                .isPositive();
    }

    // --- Corner-adjacency penalty (only while the corner is empty) ------------------------------

    @Test
    void sittingOnAnXSquareNextToAnEmptyCornerIsPenalised() {
        // a1 is empty in both. Black on b2 (the a1 X-square) must score lower than Black on a quiet
        // square (c3), purely from the X-square penalty.
        OthelloState onXSquare = new OthelloState(sq(9), sq(27), Player.BLACK, 0);  // Black b2, White d4
        OthelloState onQuietSquare = new OthelloState(sq(18), sq(27), Player.BLACK, 0); // Black c3, White d4

        assertThat(evaluator.evaluate(onXSquare, Player.BLACK))
                .isLessThan(evaluator.evaluate(onQuietSquare, Player.BLACK));
    }

    @Test
    void theXSquarePenaltyLiftsOnceTheCornerIsOwned() {
        // Black on b2 with a1 EMPTY is penalised; Black on b2 with a1 OWNED is not (and gains the
        // corner bonus on top), so the owned-corner position must score much higher.
        OthelloState cornerEmpty = new OthelloState(sq(9), sq(27), Player.BLACK, 0);          // b2, a1 empty
        OthelloState cornerOwned = new OthelloState(sq(9) | sq(0), sq(27), Player.BLACK, 0);  // b2 + a1

        assertThat(evaluator.evaluate(cornerOwned, Player.BLACK))
                .isGreaterThan(evaluator.evaluate(cornerEmpty, Player.BLACK));
    }

    // --- Phase-shifting weights -----------------------------------------------------------------

    @Test
    void mobilityDominatesTheOpeningAndParityDominatesTheEndgame() {
        int opening = 4;       // the four centre discs
        int endgame = 64;      // a full board

        // Mobility weight fades as the board fills; parity weight grows.
        assertThat(OthelloEvaluator.mobilityWeight(opening))
                .isGreaterThan(OthelloEvaluator.mobilityWeight(endgame));
        assertThat(OthelloEvaluator.parityWeight(endgame))
                .isGreaterThan(OthelloEvaluator.parityWeight(opening));

        // The crossover: mobility outweighs parity in the opening, the reverse in the endgame.
        assertThat(OthelloEvaluator.mobilityWeight(opening))
                .isGreaterThan(OthelloEvaluator.parityWeight(opening));
        assertThat(OthelloEvaluator.parityWeight(endgame))
                .isGreaterThan(OthelloEvaluator.mobilityWeight(endgame));
    }

    @Test
    void phaseWeightsAreClampedAndMonotonicAcrossTheBoardFill() {
        // Out-of-range fills clamp to the opening/endgame endpoints (no extrapolation).
        assertThat(OthelloEvaluator.mobilityWeight(0)).isEqualTo(OthelloEvaluator.mobilityWeight(4));
        assertThat(OthelloEvaluator.parityWeight(100)).isEqualTo(OthelloEvaluator.parityWeight(64));

        // Mobility weight is non-increasing and parity non-decreasing as the board fills.
        for (int filled = 4; filled < 64; filled++) {
            assertThat(OthelloEvaluator.mobilityWeight(filled + 1))
                    .isLessThanOrEqualTo(OthelloEvaluator.mobilityWeight(filled));
            assertThat(OthelloEvaluator.parityWeight(filled + 1))
                    .isGreaterThanOrEqualTo(OthelloEvaluator.parityWeight(filled));
        }
    }

    // --- Zero-sum antisymmetry (the property the search depends on) -----------------------------

    @Test
    void evaluationIsExactlyAntisymmetricAcrossPerspectives() {
        Random rng = new Random(20260629L);
        for (int i = 0; i < 300; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(60));
            assertThat(evaluator.evaluate(state, Player.BLACK))
                    .as("evaluate(s, BLACK) == -evaluate(s, WHITE) for %s", state)
                    .isEqualTo(-evaluator.evaluate(state, Player.WHITE));
        }
    }

    // --- Wires into the generic search ----------------------------------------------------------

    @Test
    void drivesTheAlphaBetaSearchToALegalMove() {
        OthelloState initial = rules.initialState();
        OthelloMove move = new AlphaBetaSearch<>(rules, evaluator, new OthelloMoveOrdering(), 4)
                .bestMove(initial);
        assertThat(rules.getLegalMoves(initial)).contains(move);
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** A legal position reached by playing {@code plies} uniformly random legal moves (auto-passing). */
    private OthelloState randomPosition(Random rng, int plies) {
        OthelloState state = rules.initialState();
        for (int i = 0; i < plies; i++) {
            if (rules.isTerminal(state)) {
                break;
            }
            List<OthelloMove> moves = rules.getLegalMoves(state);
            state = moves.isEmpty()
                    ? rules.pass(state)
                    : rules.applyMove(state, moves.get(rng.nextInt(moves.size())));
        }
        return state;
    }
}
