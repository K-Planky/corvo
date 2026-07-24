package dev.kplanky.othello.engine;

import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloMoveOrdering;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Move ordering (spec §7, M6 rung 3b). Two things are proven: (1) the ordering itself ranks squares
 * the way the spec describes, corners first, the corner-adjacent X-square last; and (2) the payoff,
 * feeding that ordering to {@link AlphaBetaSearch} makes it visit <b>strictly fewer</b> nodes than
 * the unordered baseline across a fixed suite, while still returning a move of the <b>same value</b>
 * (ordering is a pure performance hint, so it may pick a different equally-optimal move but never a
 * worse one).
 */
class MoveOrderingTest {

    private final OthelloRules rules = new OthelloRules();
    private final OthelloMoveOrdering ordering = new OthelloMoveOrdering();

    /** Disc parity from {@code perspective}'s view, the same simple evaluator the other engine tests use. */
    private static final Evaluator<OthelloState> PARITY =
            (state, perspective) -> state.count(perspective) - state.count(perspective.opponent());

    private static final int A1 = 0, B2 = 9, D3 = 19, A2 = 8, H8 = 63;

    // --- The ordering ranks squares as the spec describes ---------------------------------------

    @Test
    void cornersComeFirstAndXSquaresLast() {
        // A mixed bag of squares; ordering must surface the corners (a1, h8) ahead of the quiet
        // centre/edge squares, and bury the X-square (b2, diagonally next to the a1 corner) last.
        List<OthelloMove> input = List.of(
                OthelloMove.at(D3), OthelloMove.at(B2), OthelloMove.at(A1),
                OthelloMove.at(A2), OthelloMove.at(H8));
        List<Integer> ordered = ordering.order(null, input).stream().map(OthelloMove::square).toList();

        assertThat(ordered).startsWith(A1, H8); // both corners, highest weight, first
        assertThat(ordered).endsWith(B2);       // the X-square, lowest weight, last
        assertThat(ordered).containsExactlyInAnyOrderElementsOf(
                input.stream().map(OthelloMove::square).toList()); // a permutation, nothing added/dropped
    }

    @Test
    void shortMoveListsAreReturnedUntouched() {
        List<OthelloMove> one = List.of(OthelloMove.at(A1));
        assertThat(ordering.order(null, one)).isSameAs(one); // no needless copy/sort
        assertThat(ordering.order(null, List.of())).isEmpty();
    }

    // --- The payoff: ordered alpha-beta prunes more, for the same value -------------------------

    @Test
    void orderedAlphaBetaVisitsStrictlyFewerNodesAcrossAFixedSuiteForTheSameValue() {
        int depth = 6;
        List<OthelloState> suite = fixedSuite();

        long unorderedTotal = 0;
        long orderedTotal = 0;
        int strictlyFewer = 0;
        for (OthelloState state : suite) {
            AlphaBetaSearch<OthelloState, OthelloMove> unordered =
                    new AlphaBetaSearch<>(rules, PARITY, depth);
            OthelloMove unorderedMove = unordered.bestMove(state);

            AlphaBetaSearch<OthelloState, OthelloMove> orderedSearch =
                    new AlphaBetaSearch<>(rules, PARITY, ordering, depth);
            OthelloMove orderedMove = orderedSearch.bestMove(state);

            // Same value, ordering may choose a different optimal move but never a worse one.
            assertThat(moveValue(state, orderedMove, depth))
                    .as("ordered move is still optimal for %s", state)
                    .isEqualTo(moveValue(state, unorderedMove, depth));

            unorderedTotal += unordered.nodesEvaluated();
            orderedTotal += orderedSearch.nodesEvaluated();
            if (orderedSearch.nodesEvaluated() < unordered.nodesEvaluated()) {
                strictlyFewer++;
            }
        }

        // The headline assertion: ordering cuts the total node count over the suite.
        assertThat(orderedTotal)
                .as("ordered alpha-beta visits fewer nodes in total (%d) than unordered (%d)",
                        orderedTotal, unorderedTotal)
                .isLessThan(unorderedTotal);
        // And it wins on the clear majority of positions, not merely on aggregate. (It can't win on
        // *every* one: a small endgame tree with few replies has little or nothing left to prune.)
        assertThat(strictlyFewer).isGreaterThan(suite.size() / 2);
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** A deterministic suite: the opening plus seeded mid-game positions (each with a move to make). */
    private List<OthelloState> fixedSuite() {
        Random rng = new Random(20260629L);
        List<OthelloState> suite = new java.util.ArrayList<>();
        suite.add(rules.initialState());
        while (suite.size() < 10) {
            OthelloState state = randomPosition(rng, 6 + rng.nextInt(34));
            if (!rules.isTerminal(state) && !rules.getLegalMoves(state).isEmpty()) {
                suite.add(state);
            }
        }
        return suite;
    }

    /** A position reached by playing {@code plies} uniformly random legal moves (auto-passing). */
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

    /** The negamax value of playing {@code move} from {@code state} at the given root depth. */
    private int moveValue(OthelloState state, OthelloMove move, int depth) {
        return -negamaxValue(rules.applyMove(state, move), depth - 1);
    }

    /** Full-width negamax value (the unpruned reference) from the side-to-move's perspective. */
    private int negamaxValue(OthelloState state, int depth) {
        if (depth == 0 || rules.isTerminal(state)) {
            return PARITY.evaluate(state, rules.currentPlayer(state));
        }
        List<OthelloMove> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            return -negamaxValue(rules.pass(state), depth - 1);
        }
        int best = -Integer.MAX_VALUE;
        for (OthelloMove move : moves) {
            best = Math.max(best, -negamaxValue(rules.applyMove(state, move), depth - 1));
        }
        return best;
    }
}
