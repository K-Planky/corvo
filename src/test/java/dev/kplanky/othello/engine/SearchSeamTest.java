package dev.kplanky.othello.engine;

import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code GameRules<S,M>} + {@code Evaluator<S>} search seam (spec §6/§7, M1.7). These tests wire
 * the game-agnostic {@link GreedySearch} to the concrete Othello rules against <em>stub</em>
 * evaluators, proving the seam compiles and is parameterized by both collaborators, and that the
 * evaluator (not a hardcoded heuristic) actually drives move selection. The real Othello evaluator
 * and the negamax ladder arrive in Milestone 6.
 */
class SearchSeamTest {

    private final OthelloRules rules = new OthelloRules();

    /** Disc parity from {@code perspective}'s view, a deliberately trivial stand-in evaluator. */
    private static final Evaluator<OthelloState> DISC_PARITY =
            (state, perspective) -> state.count(perspective) - state.count(perspective.opponent());

    private static long bit(int square) {
        return 1L << square;
    }

    private List<Integer> legalSquares(OthelloState state) {
        return rules.getLegalMoves(state).stream().map(OthelloMove::square).sorted().toList();
    }

    @Test
    void seamCompilesAndReturnsALegalMoveAgainstAStubEvaluator() {
        // The core Done-when: a Search parameterized by GameRules + an arbitrary stub Evaluator.
        Evaluator<OthelloState> constantStub = (state, perspective) -> 0;
        Search<OthelloState, OthelloMove> search = new GreedySearch<>(rules, constantStub);

        OthelloMove move = search.bestMove(OthelloState.initial());

        assertThat(rules.getLegalMoves(OthelloState.initial())).contains(move);
    }

    @Test
    void theEvaluatorDrivesTheChoiceNotAHardcodedHeuristic() {
        // Black to move with exactly two legal moves of differing value:
        //   a1 (square 0): brackets b1,c1 against d1 → flips 2 → parity +4
        //   f1 (square 5): brackets g1 against h1     → flips 1 → parity +2
        long black = bit(3) | bit(7);            // d1, h1
        long white = bit(1) | bit(2) | bit(6);   // b1, c1, g1
        OthelloState state = new OthelloState(black, white, Player.BLACK, 0);
        assertThat(legalSquares(state)).containsExactly(0, 5); // pin the two-move fixture

        // Maximizing disc parity prefers the higher-flip move, a1.
        Search<OthelloState, OthelloMove> maximize = new GreedySearch<>(rules, DISC_PARITY);
        assertThat(maximize.bestMove(state).square()).isEqualTo(0);

        // Negating the evaluator flips the preference to f1, proof the evaluator drives the choice.
        Evaluator<OthelloState> negated = (s, p) -> -DISC_PARITY.evaluate(s, p);
        Search<OthelloState, OthelloMove> minimize = new GreedySearch<>(rules, negated);
        assertThat(minimize.bestMove(state).square()).isEqualTo(5);
    }

    @Test
    void bestMoveRejectsAPositionWithNoLegalMove() {
        // a1,b1 Black, no White discs → Black has nothing to bracket. The forced pass is the
        // caller's responsibility, so the search refuses rather than inventing a pass move.
        OthelloState noMoves = new OthelloState(bit(0) | bit(1), 0L, Player.BLACK, 0);
        assertThat(rules.getLegalMoves(noMoves)).isEmpty();

        Search<OthelloState, OthelloMove> search = new GreedySearch<>(rules, DISC_PARITY);
        assertThatThrownBy(() -> search.bestMove(noMoves))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNullCollaborators() {
        assertThatThrownBy(() -> new GreedySearch<>(null, DISC_PARITY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GreedySearch<>(rules, null))
                .isInstanceOf(NullPointerException.class);
    }
}
