package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terminal detection and winner determination (spec §6/§14, M1.5).
 *
 * <p>Two terminal conditions only: a consecutive <em>double</em> pass, or a full board — never a
 * single pass. Winner = higher disc count; equal ⇒ draw. A wipeout resolves through the pass path
 * with the surviving side winning, with no special terminal rule.
 */
class OthelloRulesTerminalTest {

    private final OthelloRules rules = new OthelloRules();

    private static long bit(int square) {
        return 1L << square;
    }

    // ----- terminal detection ------------------------------------------------------------------

    @Test
    void freshGameIsNotTerminal() {
        assertThat(rules.isTerminal(OthelloState.initial())).isFalse();
    }

    @Test
    void aSinglePassDoesNotEndTheGame() {
        // One pass on record is not terminal — the game ends only on a *double* pass.
        OthelloState onePass = new OthelloState(bit(0), bit(1), Player.BLACK, 1);
        assertThat(rules.isTerminal(onePass)).isFalse();
    }

    @Test
    void aConsecutiveDoublePassEndsTheGame() {
        OthelloState doublePass = new OthelloState(bit(0), bit(1), Player.BLACK, 2);
        assertThat(rules.isTerminal(doublePass)).isTrue();
    }

    @Test
    void aFullBoardEndsTheGameEvenWithNoPasses() {
        // Every square occupied (here: Black holds them all) with consecutivePasses == 0 is terminal.
        OthelloState fullBoard = new OthelloState(-1L, 0L, Player.BLACK, 0);
        assertThat(fullBoard.occupied()).isEqualTo(-1L); // all 64 squares filled
        assertThat(rules.isTerminal(fullBoard)).isTrue();
    }

    @Test
    void aDoublePassIsReachedThroughTheApplyPath() {
        // End-to-end via the engine, not a hand-built counter: a position where neither side can
        // move. a1=Black, b1=Black, the rest empty — neither colour can ever bracket the other
        // (only one colour is present), so both must pass in turn, reaching a double pass.
        OthelloState start = new OthelloState(bit(0) | bit(1), 0L, Player.BLACK, 0);
        assertThat(OthelloRules.legalMoveMask(start)).as("Black has no move").isZero();
        assertThat(rules.isTerminal(start)).isFalse();

        OthelloState afterBlackPass = rules.applyMove(start, OthelloMove.pass());
        assertThat(rules.isTerminal(afterBlackPass)).as("one pass is not terminal").isFalse();

        OthelloState afterWhitePass = rules.applyMove(afterBlackPass, OthelloMove.pass());
        assertThat(rules.isTerminal(afterWhitePass)).as("double pass ends the game").isTrue();
    }

    // ----- winner determination ----------------------------------------------------------------

    @Test
    void winnerIsEmptyForANonTerminalState() {
        assertThat(rules.winner(OthelloState.initial())).isEmpty();
    }

    @Test
    void higherDiscCountWins() {
        // Terminal by double pass; Black 3 discs, White 1 ⇒ Black wins.
        OthelloState state = new OthelloState(bit(0) | bit(1) | bit(2), bit(3), Player.BLACK, 2);
        assertThat(rules.winner(state)).contains(Player.BLACK);

        // Mirror: White ahead ⇒ White wins.
        OthelloState whiteAhead = new OthelloState(bit(3), bit(0) | bit(1) | bit(2), Player.BLACK, 2);
        assertThat(rules.winner(whiteAhead)).contains(Player.WHITE);
    }

    @Test
    void equalDiscCountIsADraw() {
        // Terminal with 2 discs each ⇒ draw ⇒ empty (the same sentinel as non-terminal, per contract).
        OthelloState draw = new OthelloState(bit(0) | bit(1), bit(2) | bit(3), Player.BLACK, 2);
        assertThat(rules.winner(draw)).isEmpty();
    }

    @Test
    void winnerOnAFullBoardCountsAllSquares() {
        // Full board: Black holds 40 squares, White 24 ⇒ Black wins (no pass involved).
        long black = 0L;
        for (int i = 0; i < 40; i++) {
            black |= bit(i);
        }
        long white = ~black; // the remaining 24 squares
        OthelloState state = new OthelloState(black, white, Player.WHITE, 0);
        assertThat(state.occupied()).isEqualTo(-1L);
        assertThat(state.count(Player.BLACK)).isEqualTo(40);
        assertThat(state.count(Player.WHITE)).isEqualTo(24);
        assertThat(rules.winner(state)).contains(Player.BLACK);
    }

    @Test
    void wipeoutResolvesToTheSurvivingSideThroughThePassPath() {
        // One side reduced to zero discs is NOT a special terminal rule. White is wiped out (zero
        // discs); Black holds a1,b1. Black to move has no legal move (nothing to bracket) and passes;
        // White, with no discs, also has no move and passes — double pass ends it, Black surviving.
        OthelloState wipeout = new OthelloState(bit(0) | bit(1), 0L, Player.BLACK, 0);
        assertThat(wipeout.count(Player.WHITE)).isZero();
        assertThat(rules.isTerminal(wipeout)).as("not terminal until the double pass plays out").isFalse();

        OthelloState afterBlackPass = rules.applyMove(wipeout, OthelloMove.pass());
        OthelloState terminal = rules.applyMove(afterBlackPass, OthelloMove.pass());

        assertThat(rules.isTerminal(terminal)).isTrue();
        Optional<Player> winner = rules.winner(terminal);
        assertThat(winner).contains(Player.BLACK); // the surviving side wins
    }
}
