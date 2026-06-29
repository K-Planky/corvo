package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;

/**
 * Othello implementation of {@link GameRules} (spec §6). Built incrementally across Milestone 1:
 * the generic seam, legal-move generation, disc flipping for placements, the forced-pass rule and
 * terminal detection / winner determination are in place. The full {@link GameRules} contract is now
 * implemented; later Milestone-1 tasks add the {@code Evaluator} seam and a randomized playout test.
 */
public class OthelloRules implements GameRules<OthelloState, OthelloMove> {

    @Override
    public OthelloState initialState() {
        return OthelloState.initial();
    }

    @Override
    public Player currentPlayer(OthelloState state) {
        return state.toMove();
    }

    @Override
    public List<OthelloMove> getLegalMoves(OthelloState state) {
        long mask = legalMoveMask(state);
        List<OthelloMove> moves = new ArrayList<>(Long.bitCount(mask));
        while (mask != 0L) {
            int square = Long.numberOfTrailingZeros(mask);
            moves.add(OthelloMove.at(square));
            mask &= mask - 1; // clear the lowest set bit
        }
        return moves;
    }

    /**
     * Bitboard of all squares on which the side to move may legally place a disc.
     *
     * <p>For each of the eight directions: starting from the mover's discs, walk across a
     * contiguous run of opponent discs; any empty square immediately past such a run is a legal
     * move (it brackets that run). All shifts are edge-masked, so no run wraps across a board edge.
     */
    static long legalMoveMask(OthelloState state) {
        long mine = state.discs(state.toMove());
        long theirs = state.discs(state.toMove().opponent());
        long empty = ~state.occupied();

        long moves = 0L;
        for (LongUnaryOperator shift : Bitboards.DIRECTIONS) {
            // Opponent discs directly adjacent to one of ours, then extend along the run.
            long run = shift.applyAsLong(mine) & theirs;
            for (int i = 0; i < 5; i++) { // a run spans at most 6 opponent discs in a line of 8
                run |= shift.applyAsLong(run) & theirs;
            }
            // An empty square just past the run brackets it: a legal move.
            moves |= shift.applyAsLong(run) & empty;
        }
        return moves;
    }

    @Override
    public OthelloState applyMove(OthelloState state, OthelloMove move) {
        if (move.isPass()) {
            // A pass is legal only when the side to move has no legal placement (spec §6/§14). A
            // player with ≥1 legal move may not pass; the engine rejects such an illegal pass (this
            // is what the §9 "illegal pass" 422 rule presupposes). A legal pass flips nothing,
            // advances the turn, and increments the consecutive-pass counter that drives double-pass
            // termination (M1.5).
            if (legalMoveMask(state) != 0L) {
                throw new IllegalArgumentException("illegal pass: the side to move has a legal move");
            }
            return new OthelloState(
                    state.black(), state.white(), state.toMove().opponent(), state.consecutivePasses() + 1);
        }
        int square = move.square();
        if ((state.occupied() & OthelloState.bit(square)) != 0L) {
            throw new IllegalArgumentException("illegal move: square " + square + " is occupied");
        }
        long flipped = flips(state, square);
        if (flipped == 0L) {
            throw new IllegalArgumentException("illegal move: square " + square + " brackets nothing");
        }

        Player mover = state.toMove();
        long placed = OthelloState.bit(square);
        long mine = state.discs(mover) | placed | flipped;
        long theirs = state.discs(mover.opponent()) & ~flipped;
        long black = mover == Player.BLACK ? mine : theirs;
        long white = mover == Player.BLACK ? theirs : mine;
        // A real placement always resets the consecutive-pass counter (only a pass increments it);
        // turn passes to the opponent.
        return new OthelloState(black, white, mover.opponent(), 0);
    }

    /**
     * Advances a forced pass for the side to move (spec §6). Delegates to {@link #applyMove} with a
     * pass move so the "a pass is legal only with zero legal placements" invariant lives in exactly
     * one place: a pass from a position that <em>has</em> a legal move is rejected there.
     */
    @Override
    public OthelloState pass(OthelloState state) {
        return applyMove(state, OthelloMove.pass());
    }

    /**
     * Bitboard of opponent discs flipped by placing a disc on {@code square} (0..63) for the side to
     * move. Empty (0) means the placement brackets nothing — i.e. it is not a legal move.
     *
     * <p>For each of the eight directions, walk the contiguous run of opponent discs starting just
     * past {@code square}; if that run is terminated by one of the mover's own discs, the whole run
     * is captured. All shifts are edge-masked, so a run never wraps across a board edge.
     */
    static long flips(OthelloState state, int square) {
        long mine = state.discs(state.toMove());
        long theirs = state.discs(state.toMove().opponent());
        long placed = OthelloState.bit(square);

        long captured = 0L;
        for (LongUnaryOperator shift : Bitboards.DIRECTIONS) {
            long run = shift.applyAsLong(placed) & theirs;
            for (int i = 0; i < 5; i++) { // a run spans at most 6 opponent discs in a line of 8
                run |= shift.applyAsLong(run) & theirs;
            }
            // The run flips only if the square just past its far end holds one of our discs.
            if ((shift.applyAsLong(run) & mine) != 0L) {
                captured |= run;
            }
        }
        return captured;
    }

    /** Every square occupied — all 64 bits set. */
    private static final long FULL_BOARD = -1L;

    /**
     * Whether the game has ended (spec §6/§14). Exactly two terminal conditions:
     *
     * <ul>
     *   <li><b>Double pass</b> — both players passed in succession ({@code consecutivePasses == 2}).
     *       A <em>single</em> pass does not end the game.</li>
     *   <li><b>Board full</b> — every square is occupied.</li>
     * </ul>
     *
     * <p>A wipeout (one side reduced to zero discs) is <em>not</em> a special terminal rule: the
     * wiped side has no legal move, so it passes, and the game still resolves through the double-pass
     * or board-full path above (the spec pins this explicitly).
     */
    @Override
    public boolean isTerminal(OthelloState state) {
        return state.consecutivePasses() >= 2 || state.occupied() == FULL_BOARD;
    }

    /**
     * The winner of a terminal game, or empty for a draw <em>or</em> a non-terminal state (spec
     * §6/§14). The winner is simply whoever holds more discs; an equal count is a draw. This also
     * resolves a wipeout correctly — the surviving side has strictly more discs.
     */
    @Override
    public Optional<Player> winner(OthelloState state) {
        if (!isTerminal(state)) {
            return Optional.empty();
        }
        int black = state.count(Player.BLACK);
        int white = state.count(Player.WHITE);
        if (black > white) {
            return Optional.of(Player.BLACK);
        }
        if (white > black) {
            return Optional.of(Player.WHITE);
        }
        return Optional.empty(); // equal disc counts ⇒ draw
    }
}
