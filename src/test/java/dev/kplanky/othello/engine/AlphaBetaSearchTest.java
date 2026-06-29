package dev.kplanky.othello.engine;

import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alpha-beta pruning (spec §7, M6 rung 3) — the two AI-engine tests the spec names for this rung,
 * both measured against rung 2 ({@link NegamaxSearch}): (1) <b>correctness</b> — alpha-beta returns
 * the same best move as full-width negamax for the same position+depth, across a hand-checked fixture
 * and a fuzz of random positions/depths; and (2) the <b>perf assertion</b> — alpha-beta evaluates
 * <em>strictly fewer</em> nodes than negamax wherever the branching admits pruning. Negamax already
 * cross-checks itself against an independent textbook minimax, so matching negamax move-for-move
 * transitively pins alpha-beta to the true optimum.
 */
class AlphaBetaSearchTest {

    private final OthelloRules rules = new OthelloRules();

    /** Disc parity from {@code perspective}'s view — the same simple evaluator the negamax test uses. */
    private static final Evaluator<OthelloState> PARITY =
            (state, perspective) -> state.count(perspective) - state.count(perspective.opponent());

    private static long bit(int square) {
        return 1L << square;
    }

    // --- Correctness: hand-checked shallow position ---------------------------------------------

    @Test
    void depthOnePicksTheKnownOptimalMove() {
        // Black to move with exactly two legal moves: a1 (square 0) flips 2 → parity +4;
        // f1 (square 5) flips 1 → parity +2. A 1-ply maximiser of parity must choose a1.
        long black = bit(3) | bit(7);            // d1, h1
        long white = bit(1) | bit(2) | bit(6);   // b1, c1, g1
        OthelloState state = new OthelloState(black, white, Player.BLACK, 0);
        assertThat(squares(rules.getLegalMoves(state))).containsExactly(0, 5);

        OthelloMove best = new AlphaBetaSearch<>(rules, PARITY, 1).bestMove(state);
        assertThat(best.square()).isEqualTo(0);
    }

    // --- Correctness: identical best move to full-width negamax ----------------------------------

    @Test
    void alphaBetaPicksTheSameMoveAsNegamaxAcrossRandomPositionsAndDepths() {
        Random rng = new Random(20260629L);
        int positionsChecked = 0;
        for (int i = 0; i < 200; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(50));
            if (rules.isTerminal(state) || rules.getLegalMoves(state).isEmpty()) {
                continue; // the root forced-pass case is the caller's job, not the search's
            }
            for (int depth = 1; depth <= 5; depth++) {
                OthelloMove negamax = new NegamaxSearch<>(rules, PARITY, depth).bestMove(state);
                OthelloMove alphaBeta = new AlphaBetaSearch<>(rules, PARITY, depth).bestMove(state);
                assertThat(alphaBeta)
                        .as("alpha-beta == negamax at depth %d for %s", depth, state)
                        .isEqualTo(negamax);
            }
            positionsChecked++;
        }
        assertThat(positionsChecked).isGreaterThan(50); // the cross-check actually ran
    }

    @Test
    void internalForcedPassIsTraversedNotMishandled() {
        // A position whose mover has a move that hands the opponent a non-terminal position with no
        // legal move (a forced pass inside the tree). Alpha-beta must walk GameRules.pass without
        // throwing and still agree with negamax.
        Random rng = new Random(7L);
        boolean covered = false;
        for (int i = 0; i < 5000 && !covered; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(56));
            List<OthelloMove> moves = rules.getLegalMoves(state);
            if (rules.isTerminal(state) || moves.isEmpty()) {
                continue;
            }
            for (OthelloMove move : moves) {
                OthelloState child = rules.applyMove(state, move);
                if (!rules.isTerminal(child) && rules.getLegalMoves(child).isEmpty()) {
                    OthelloMove negamax = new NegamaxSearch<>(rules, PARITY, 2).bestMove(state);
                    OthelloMove alphaBeta = new AlphaBetaSearch<>(rules, PARITY, 2).bestMove(state);
                    assertThat(moves).contains(alphaBeta);
                    assertThat(alphaBeta).isEqualTo(negamax);
                    covered = true;
                    break;
                }
            }
        }
        assertThat(covered).as("a tree-internal forced pass was actually exercised").isTrue();
    }

    // --- Perf: strictly fewer nodes than negamax ------------------------------------------------

    @Test
    void alphaBetaVisitsStrictlyFewerNodesThanNegamaxOnTheInitialPosition() {
        // Negamax's hand-counted baseline at depth 2 is 16 nodes; pruning must do better.
        OthelloState initial = rules.initialState();

        NegamaxSearch<OthelloState, OthelloMove> negamax = new NegamaxSearch<>(rules, PARITY, 2);
        negamax.bestMove(initial);

        AlphaBetaSearch<OthelloState, OthelloMove> alphaBeta = new AlphaBetaSearch<>(rules, PARITY, 2);
        alphaBeta.bestMove(initial);

        assertThat(negamax.nodesEvaluated()).isEqualTo(16);
        assertThat(alphaBeta.nodesEvaluated()).isLessThan(negamax.nodesEvaluated());
    }

    @Test
    void alphaBetaNeverVisitsMoreNodesAndUsuallyFewerAcrossRandomPositions() {
        // Across a fuzz of positions at a pruning-friendly depth, alpha-beta must never exceed
        // negamax's node count and must strictly beat it on the large majority (pruning is the point).
        Random rng = new Random(424242L);
        int compared = 0;
        int strictlyFewer = 0;
        for (int i = 0; i < 120; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(40));
            if (rules.isTerminal(state) || rules.getLegalMoves(state).isEmpty()) {
                continue;
            }
            NegamaxSearch<OthelloState, OthelloMove> negamax = new NegamaxSearch<>(rules, PARITY, 4);
            negamax.bestMove(state);
            AlphaBetaSearch<OthelloState, OthelloMove> alphaBeta = new AlphaBetaSearch<>(rules, PARITY, 4);
            alphaBeta.bestMove(state);

            assertThat(alphaBeta.nodesEvaluated())
                    .as("alpha-beta never visits more nodes than negamax for %s", state)
                    .isLessThanOrEqualTo(negamax.nodesEvaluated());
            if (alphaBeta.nodesEvaluated() < negamax.nodesEvaluated()) {
                strictlyFewer++;
            }
            compared++;
        }
        assertThat(compared).isGreaterThan(40);
        // Pruning should win on essentially every multi-move position; allow tiny slack for the rare
        // position with a single forced reply where there is nothing to prune.
        assertThat(strictlyFewer).isGreaterThan((compared * 9) / 10);
    }

    // --- Contract -------------------------------------------------------------------------------

    @Test
    void bestMoveRejectsAPositionWithNoLegalMove() {
        OthelloState noMoves = new OthelloState(bit(0) | bit(1), 0L, Player.BLACK, 0);
        assertThat(rules.getLegalMoves(noMoves)).isEmpty();
        assertThatThrownBy(() -> new AlphaBetaSearch<>(rules, PARITY, 3).bestMove(noMoves))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadConstructorArguments() {
        assertThatThrownBy(() -> new AlphaBetaSearch<>(null, PARITY, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AlphaBetaSearch<>(rules, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AlphaBetaSearch<>(rules, PARITY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private List<Integer> squares(List<OthelloMove> moves) {
        return moves.stream().map(OthelloMove::square).sorted().toList();
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
}
