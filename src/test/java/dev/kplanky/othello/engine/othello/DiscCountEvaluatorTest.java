package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Easy tier's beginner's-eye evaluation (spec §7): the score is the raw disc difference and
 * nothing else, corners and trap squares are worth exactly one disc, and it keeps the zero-sum
 * antisymmetry every evaluator in the engine promises.
 */
class DiscCountEvaluatorTest {

    private final DiscCountEvaluator evaluator = new DiscCountEvaluator();
    private final OthelloRules rules = new OthelloRules();

    private static long sq(int square) {
        return 1L << square;
    }

    @Test
    void scoreIsTheRawDiscDifference() {
        // Black b1+c1+d1 (3 discs) vs White d4 (1 disc): +2 for Black, -2 for White.
        OthelloState state = new OthelloState(sq(1) | sq(2) | sq(3), sq(27), Player.BLACK, 0);

        assertThat(evaluator.evaluate(state, Player.BLACK)).isEqualTo(2);
        assertThat(evaluator.evaluate(state, Player.WHITE)).isEqualTo(-2);
    }

    @Test
    void cornersAndTrapSquaresAreWorthExactlyOneDisc() {
        // The whole point of the beginner heuristic: a corner (a1) and the X-square next to it (b2)
        // score identically to a quiet square (d3), no positional judgement at all.
        OthelloState onCorner = new OthelloState(sq(0), sq(27), Player.BLACK, 0);
        OthelloState onXSquare = new OthelloState(sq(9), sq(27), Player.BLACK, 0);
        OthelloState onQuietSquare = new OthelloState(sq(19), sq(27), Player.BLACK, 0);

        assertThat(evaluator.evaluate(onCorner, Player.BLACK))
                .isEqualTo(evaluator.evaluate(onXSquare, Player.BLACK))
                .isEqualTo(evaluator.evaluate(onQuietSquare, Player.BLACK));
    }

    @Test
    void evaluationIsExactlyAntisymmetricAcrossPerspectives() {
        Random rng = new Random(20260705L);
        for (int i = 0; i < 100; i++) {
            OthelloState state = randomPosition(rng, rng.nextInt(60));
            assertThat(evaluator.evaluate(state, Player.BLACK))
                    .as("evaluate(s, BLACK) == -evaluate(s, WHITE) for %s", state)
                    .isEqualTo(-evaluator.evaluate(state, Player.WHITE));
        }
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
