package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-style randomized self-play (spec §6/§14, M1.6). Rather than hand-checking positions,
 * this drives many full games of uniformly-random legal moves through the engine and asserts the
 * invariants that must hold across <em>every</em> reachable position: the two bitboards never
 * overlap, the disc total never exceeds 64, every game terminates, and the reported winner agrees
 * with the final disc counts.
 *
 * <p>Termination is guaranteed because each placement fills exactly one empty square (flips don't
 * change occupancy) and occupancy is capped at 64, while a position with no move passes — two
 * passes in a row end the game. The ply cap below is only a safety net so a regression that broke
 * termination fails fast instead of hanging.
 */
class OthelloRulesPlayoutTest {

    private static final int GAMES = 500;
    private static final int PLY_CAP = 200; // generous: a real game is ≤ ~60 placements + a few passes

    private final OthelloRules rules = new OthelloRules();

    @Test
    void randomPlayoutsAlwaysTerminateWithConsistentDiscCounts() {
        Random random = new Random(42); // fixed seed → reproducible failures

        for (int game = 0; game < GAMES; game++) {
            playOneGame(random, game);
        }
    }

    private void playOneGame(Random random, int gameIndex) {
        OthelloState state = OthelloState.initial();
        assertInvariants(state, gameIndex, 0);

        int ply = 0;
        while (!rules.isTerminal(state)) {
            assertThat(ply)
                    .as("game %d exceeded the ply cap — likely a non-terminating engine", gameIndex)
                    .isLessThan(PLY_CAP);

            List<OthelloMove> legal = rules.getLegalMoves(state);
            OthelloMove move = legal.isEmpty()
                    ? OthelloMove.pass()                       // forced pass when no legal move exists
                    : legal.get(random.nextInt(legal.size())); // otherwise a uniformly-random legal move
            state = rules.applyMove(state, move);
            ply++;

            assertInvariants(state, gameIndex, ply);
        }

        assertWinnerConsistentWithCounts(state, gameIndex);
    }

    /** Invariants that must hold in every position reached during a playout. */
    private void assertInvariants(OthelloState state, int gameIndex, int ply) {
        String where = String.format("game %d, ply %d", gameIndex, ply);

        // The two bitboards are disjoint: no square holds both a black and a white disc.
        assertThat(state.black() & state.white())
                .as("%s: black and white bitboards overlap", where)
                .isZero();

        int black = state.count(Player.BLACK);
        int white = state.count(Player.WHITE);

        // The disc total never exceeds the 64 squares, and equals the popcount of the union.
        assertThat(black + white)
                .as("%s: disc total exceeds the board", where)
                .isLessThanOrEqualTo(64)
                .isEqualTo(Long.bitCount(state.occupied()));

        assertThat(state.consecutivePasses())
                .as("%s: consecutivePasses out of range", where)
                .isBetween(0, 2);
    }

    private void assertWinnerConsistentWithCounts(OthelloState state, int gameIndex) {
        int black = state.count(Player.BLACK);
        int white = state.count(Player.WHITE);
        Optional<Player> winner = rules.winner(state);

        if (black > white) {
            assertThat(winner).as("game %d: black ahead but winner not black", gameIndex)
                    .contains(Player.BLACK);
        } else if (white > black) {
            assertThat(winner).as("game %d: white ahead but winner not white", gameIndex)
                    .contains(Player.WHITE);
        } else {
            assertThat(winner).as("game %d: equal discs but not a draw", gameIndex).isEmpty();
        }

        // A finished game always has at least the four opening discs and never an empty board.
        assertThat(black + white)
                .as("game %d: terminal game has too few discs", gameIndex)
                .isGreaterThanOrEqualTo(4);
    }
}
