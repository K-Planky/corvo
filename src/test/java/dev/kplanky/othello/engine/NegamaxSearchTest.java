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
 * Negamax full-width search (spec §7, M6 rung 2). Two things are proven: (1) <b>correctness</b> —
 * negamax returns the same move as an independent textbook min/max reference across many positions
 * and depths, plus a hand-checked shallow fixture; and (2) the <b>node-count baseline</b> that the
 * next rung (alpha-beta) must beat — pinned on the initial position where the tree is hand-countable.
 */
class NegamaxSearchTest {

    private final OthelloRules rules = new OthelloRules();

    /** Disc parity from {@code perspective}'s view — a simple, exactly-reasoned-about evaluator. */
    private static final Evaluator<OthelloState> PARITY =
            (state, perspective) -> state.count(perspective) - state.count(perspective.opponent());

    private static long bit(int square) {
        return 1L << square;
    }

    // --- Correctness: hand-checked shallow position ---------------------------------------------

    @Test
    void depthOneNegamaxPicksTheKnownOptimalMove() {
        // Same fixture as the seam test: Black to move with exactly two legal moves —
        //   a1 (square 0) flips 2 discs → parity +4;  f1 (square 5) flips 1 → parity +2.
        // A 1-ply negamax maximising parity must choose a1.
        long black = bit(3) | bit(7);            // d1, h1
        long white = bit(1) | bit(2) | bit(6);   // b1, c1, g1
        OthelloState state = new OthelloState(black, white, Player.BLACK, 0);
        assertThat(squares(rules.getLegalMoves(state))).containsExactly(0, 5);

        OthelloMove best = new NegamaxSearch<>(rules, PARITY, 1).bestMove(state);
        assertThat(best.square()).isEqualTo(0);
    }

    // --- Correctness: cross-check against an independent textbook minimax ------------------------

    @Test
    void negamaxAgreesWithTextbookMinimaxAcrossRandomPositionsAndDepths() {
        Random rng = new Random(20260629L);
        int positionsChecked = 0;
        for (int i = 0; i < 200; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(50));
            if (rules.isTerminal(state) || rules.getLegalMoves(state).isEmpty()) {
                continue; // the root forced-pass case is the caller's job, not the search's
            }
            for (int depth = 1; depth <= 4; depth++) {
                OthelloMove negamax = new NegamaxSearch<>(rules, PARITY, depth).bestMove(state);
                assertThat(negamax)
                        .as("negamax == minimax at depth %d for %s", depth, state)
                        .isEqualTo(referenceBestMove(state, depth));
            }
            positionsChecked++;
        }
        assertThat(positionsChecked).isGreaterThan(50); // the cross-check actually ran
    }

    @Test
    void internalForcedPassIsTraversedNotMishandled() {
        // Find a reachable position whose side to move has a legal move that hands the opponent a
        // non-terminal position with *no* legal move (a forced pass inside the search tree). A
        // depth-2 search from there must walk through GameRules.pass without throwing and still
        // match the reference.
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
                    OthelloMove best = new NegamaxSearch<>(rules, PARITY, 2).bestMove(state);
                    assertThat(moves).contains(best);
                    assertThat(best).isEqualTo(referenceBestMove(state, 2));
                    covered = true;
                    break;
                }
            }
        }
        assertThat(covered).as("a tree-internal forced pass was actually exercised").isTrue();
    }

    // --- Node-count baseline (hand-countable on the initial position) ---------------------------

    @Test
    void nodeCountBaselineIsHandCountableOnTheInitialPosition() {
        // Black has 4 opening moves; after any of them White always has exactly 3 replies.
        // Counting one node per searched child: depth 1 = 4 leaves; depth 2 = 4 + 4*3 = 16.
        OthelloState initial = rules.initialState();
        assertThat(rules.getLegalMoves(initial)).hasSize(4);

        NegamaxSearch<OthelloState, OthelloMove> d1 = new NegamaxSearch<>(rules, PARITY, 1);
        d1.bestMove(initial);
        assertThat(d1.nodesEvaluated()).isEqualTo(4);

        NegamaxSearch<OthelloState, OthelloMove> d2 = new NegamaxSearch<>(rules, PARITY, 2);
        d2.bestMove(initial);
        assertThat(d2.nodesEvaluated()).isEqualTo(16);

        // The count is reset per search and grows with depth (more of the tree visited).
        NegamaxSearch<OthelloState, OthelloMove> d3 = new NegamaxSearch<>(rules, PARITY, 3);
        d3.bestMove(initial);
        assertThat(d3.nodesEvaluated()).isGreaterThan(16);
    }

    // --- Contract -------------------------------------------------------------------------------

    @Test
    void bestMoveRejectsAPositionWithNoLegalMove() {
        OthelloState noMoves = new OthelloState(bit(0) | bit(1), 0L, Player.BLACK, 0);
        assertThat(rules.getLegalMoves(noMoves)).isEmpty();
        assertThatThrownBy(() -> new NegamaxSearch<>(rules, PARITY, 3).bestMove(noMoves))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadConstructorArguments() {
        assertThatThrownBy(() -> new NegamaxSearch<>(null, PARITY, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NegamaxSearch<>(rules, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NegamaxSearch<>(rules, PARITY, 0))
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

    /**
     * Independent textbook minimax: a global, Black-relative position value with explicit max nodes
     * (Black to move) and min nodes (White to move). Deliberately <em>not</em> negamax, so it is a
     * genuine cross-check rather than a re-implementation of the code under test.
     */
    private OthelloMove referenceBestMove(OthelloState state, int depth) {
        Player toMove = rules.currentPlayer(state);
        OthelloMove best = null;
        int bestValue = 0;
        for (OthelloMove move : rules.getLegalMoves(state)) {
            int value = referenceValue(rules.applyMove(state, move), depth - 1);
            boolean better = best == null
                    || (toMove == Player.BLACK ? value > bestValue : value < bestValue);
            if (better) {
                bestValue = value;
                best = move;
            }
        }
        return best;
    }

    private int referenceValue(OthelloState state, int depth) {
        if (depth == 0 || rules.isTerminal(state)) {
            return state.count(Player.BLACK) - state.count(Player.WHITE);
        }
        List<OthelloMove> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            return referenceValue(rules.pass(state), depth - 1);
        }
        boolean maximizing = rules.currentPlayer(state) == Player.BLACK;
        int value = maximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (OthelloMove move : moves) {
            int child = referenceValue(rules.applyMove(state, move), depth - 1);
            value = maximizing ? Math.max(value, child) : Math.min(value, child);
        }
        return value;
    }
}
