package dev.kplanky.othello.engine;

import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The difficulty ladder's blunder decorator (spec §7): with probability ε it plays a random legal
 * move, otherwise the wrapped search's move. The endpoints are exact (ε = 0 always delegates,
 * ε = 1 never does), a seeded mid-range ε takes both branches, and — like every search in the
 * engine — it never returns an illegal move.
 */
class EpsilonRandomSearchTest {

    private final OthelloRules rules = new OthelloRules();

    private static long sq(int square) {
        return 1L << square;
    }

    /** A delegate that always plays the last legal move and counts how often it was consulted. */
    private static final class CountingDelegate implements Search<OthelloState, OthelloMove> {
        final AtomicInteger calls = new AtomicInteger();
        final OthelloRules rules;

        CountingDelegate(OthelloRules rules) {
            this.rules = rules;
        }

        @Override
        public OthelloMove bestMove(OthelloState state) {
            calls.incrementAndGet();
            List<OthelloMove> moves = rules.getLegalMoves(state);
            return moves.get(moves.size() - 1);
        }
    }

    @Test
    void epsilonZeroAlwaysPlaysTheDelegatesMove() {
        OthelloState initial = rules.initialState();
        CountingDelegate delegate = new CountingDelegate(rules);
        var search = new EpsilonRandomSearch<>(rules, delegate, 0.0, new Random(1L));

        OthelloMove expected = delegate.bestMove(initial);
        delegate.calls.set(0);
        for (int i = 0; i < 50; i++) {
            assertThat(search.bestMove(initial)).isEqualTo(expected);
        }
        assertThat(delegate.calls.get()).isEqualTo(50);
    }

    @Test
    void epsilonOneNeverConsultsTheDelegateAndStaysLegal() {
        OthelloState initial = rules.initialState();
        CountingDelegate delegate = new CountingDelegate(rules);
        var search = new EpsilonRandomSearch<>(rules, delegate, 1.0, new Random(2L));

        for (int i = 0; i < 50; i++) {
            assertThat(rules.getLegalMoves(initial)).contains(search.bestMove(initial));
        }
        assertThat(delegate.calls.get()).isZero();
    }

    @Test
    void aMidRangeEpsilonTakesBothBranches() {
        OthelloState initial = rules.initialState();
        CountingDelegate delegate = new CountingDelegate(rules);
        var search = new EpsilonRandomSearch<>(rules, delegate, 0.3, new Random(3L));

        int rounds = 200;
        for (int i = 0; i < rounds; i++) {
            assertThat(rules.getLegalMoves(initial)).contains(search.bestMove(initial));
        }
        // Seeded, so exact counts are stable; assert the property, not the seed's arithmetic.
        assertThat(delegate.calls.get())
                .as("both the delegate branch and the random branch fired")
                .isGreaterThan(0)
                .isLessThan(rounds);
    }

    @Test
    void neverReturnsAnIllegalMoveAcrossRandomPositions() {
        Random rng = new Random(20260705L);
        var search = new EpsilonRandomSearch<>(rules, new CountingDelegate(rules), 0.5, rng);
        int checked = 0;
        for (int i = 0; i < 200; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(60));
            if (rules.isTerminal(state) || rules.getLegalMoves(state).isEmpty()) {
                continue; // a forced pass is the caller's job, not the search's
            }
            assertThat(rules.getLegalMoves(state))
                    .as("epsilon-random returned a legal move for %s", state)
                    .contains(search.bestMove(state));
            checked++;
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    void refusesToChooseWhenThereIsNoLegalMove() {
        // Black b2 vs White d4: no flippable line anywhere, so Black to move has no legal move.
        OthelloState noMoves = new OthelloState(sq(9), sq(27), Player.BLACK, 0);
        var search = new EpsilonRandomSearch<>(rules, new CountingDelegate(rules), 1.0, new Random(4L));

        assertThatThrownBy(() -> search.bestMove(noMoves))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pass");
    }

    @Test
    void rejectsAnEpsilonOutsideTheUnitRange() {
        CountingDelegate delegate = new CountingDelegate(rules);
        assertThatThrownBy(() -> new EpsilonRandomSearch<>(rules, delegate, -0.1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpsilonRandomSearch<>(rules, delegate, 1.1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpsilonRandomSearch<>(rules, delegate, Double.NaN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
