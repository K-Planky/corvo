package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;

/**
 * Othello implementation of {@link GameRules} (spec §6). Built incrementally across Milestone 1:
 * this task wires the generic seam plus legal-move generation; disc flipping, the pass rule,
 * terminal detection and winner determination land in subsequent tasks.
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
        throw new UnsupportedOperationException("applyMove arrives in M1.3 (disc flipping)");
    }

    @Override
    public boolean isTerminal(OthelloState state) {
        throw new UnsupportedOperationException("isTerminal arrives in M1.5 (terminal detection)");
    }

    @Override
    public Optional<Player> winner(OthelloState state) {
        throw new UnsupportedOperationException("winner arrives in M1.5 (winner determination)");
    }
}
