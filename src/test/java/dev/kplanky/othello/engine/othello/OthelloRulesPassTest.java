package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Forced-pass rule and pass-as-a-move (spec §6/§14, M1.4). A player passes only with zero legal
 * moves; a player with a legal move may not pass. A legal pass flips nothing, advances the turn,
 * and increments the consecutive-pass counter that drives double-pass termination (M1.5).
 */
class OthelloRulesPassTest {

    private final OthelloRules rules = new OthelloRules();

    private static long bit(int square) {
        return 1L << square;
    }

    /**
     * A Black-to-move position in which Black has no legal move but White does. Squares: a1=White,
     * b1=White, c1=Black. Black's only disc is c1; the White run b1,a1 sits to its west and runs off
     * the board edge, so there is no empty square Black can play to bracket it, Black must pass.
     * White, in contrast, can play d1 (d1→c1 Black→b1 White brackets), so this fixture proves the
     * asymmetry rather than a dead position where both sides are stuck.
     */
    private static OthelloState blackHasNoMoveWhiteDoes(int consecutivePasses) {
        long black = bit(2);            // c1
        long white = bit(0) | bit(1);   // a1, b1
        OthelloState state = new OthelloState(black, white, Player.BLACK, consecutivePasses);
        // Guard the fixture: the whole test is meaningless if Black actually has a move (or White
        // doesn't), so pin both halves of the asymmetry here.
        assertThat(OthelloRules.legalMoveMask(state)).as("Black must have no legal move").isZero();
        OthelloState whiteToMove = new OthelloState(black, white, Player.WHITE, 0);
        assertThat(OthelloRules.legalMoveMask(whiteToMove)).as("White must have a legal move").isNotZero();
        return state;
    }

    @Test
    void aPlayerWithZeroLegalMovesMayPass() {
        OthelloState state = blackHasNoMoveWhiteDoes(0);

        OthelloState next = rules.applyMove(state, OthelloMove.pass());

        // The board is untouched (a pass flips nothing) ...
        assertThat(next.black()).isEqualTo(state.black());
        assertThat(next.white()).isEqualTo(state.white());
        // ... the turn advances to the opponent ...
        assertThat(next.toMove()).isEqualTo(Player.WHITE);
        // ... and the consecutive-pass counter increments (feeds double-pass termination in M1.5).
        assertThat(next.consecutivePasses()).isEqualTo(1);
    }

    @Test
    void consecutivePassesAccumulateAcrossSuccessivePasses() {
        // Starting from a state that already has one pass recorded, a second legal pass makes two.
        OthelloState state = blackHasNoMoveWhiteDoes(1);

        OthelloState next = rules.applyMove(state, OthelloMove.pass());

        assertThat(next.consecutivePasses()).isEqualTo(2);
    }

    @Test
    void aPlayerWithALegalMoveMayNotPass() {
        // From the initial position Black has four legal moves, so an explicit pass is illegal and
        // the engine rejects it (the contract behind the §9 "illegal pass" 422).
        OthelloState start = OthelloState.initial();
        assertThat(OthelloRules.legalMoveMask(start)).isNotZero();

        assertThatThrownBy(() -> rules.applyMove(start, OthelloMove.pass()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal pass");
    }

    @Test
    void getLegalMovesIsEmptyExactlyWhenThePlayerMustPass() {
        // The engine contract: an empty legal-move list is the signal that the side to move must
        // pass. Tie the two together so callers can rely on it.
        OthelloState mustPass = blackHasNoMoveWhiteDoes(0);
        assertThat(rules.getLegalMoves(mustPass)).isEmpty();

        OthelloState canMove = OthelloState.initial();
        assertThat(rules.getLegalMoves(canMove)).isNotEmpty();
    }

    @Test
    void theOpponentCanMoveAfterAForcedPass() {
        // The whole point of the pass is to hand the turn to a player who *can* move: after Black's
        // forced pass, White has a legal move and plays it through the normal apply path.
        OthelloState afterPass = rules.applyMove(blackHasNoMoveWhiteDoes(0), OthelloMove.pass());
        assertThat(afterPass.toMove()).isEqualTo(Player.WHITE);

        long whiteMoves = OthelloRules.legalMoveMask(afterPass);
        assertThat(whiteMoves).isNotZero();
        int sq = Long.numberOfTrailingZeros(whiteMoves);

        OthelloState afterMove = rules.applyMove(afterPass, OthelloMove.at(sq));
        // A real placement resets the consecutive-pass counter, so a later lone pass is not mistaken
        // for a double pass.
        assertThat(afterMove.consecutivePasses()).isZero();
        assertThat(afterMove.toMove()).isEqualTo(Player.BLACK);
    }

    @Test
    void aPlacementResetsAnAccumulatedPassCounter() {
        // Independently of the pass path: a placement from a state carrying a pass count zeroes it.
        OthelloState withPass = new OthelloState(
                OthelloState.initial().black(), OthelloState.initial().white(), Player.BLACK, 1);

        OthelloState next = rules.applyMove(withPass, OthelloMove.at(19)); // Black plays d3

        assertThat(next.consecutivePasses()).isZero();
    }
}
