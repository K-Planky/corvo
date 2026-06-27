package dev.kplanky.othello.engine;

import java.util.List;
import java.util.Optional;

/**
 * Game-agnostic rules engine (spec §6). The AI search and the real-time game loop depend on
 * this interface, <em>not</em> on Othello directly, so a second game (chess, Gomoku) can be added
 * later by supplying its own {@code GameRules} plus an {@code Evaluator} (added in a later task).
 *
 * <p>Implementations are expected to be pure: {@link #applyMove(Object, Object)} returns a new
 * state rather than mutating its argument (immutable preferred).
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public interface GameRules<S, M> {

    /** The starting position with the side-to-move set to whoever moves first. */
    S initialState();

    /** The side whose turn it is in {@code state}. */
    Player currentPlayer(S state);

    /** Legal moves for the current player; an empty list means the current player must pass. */
    List<M> getLegalMoves(S state);

    /** Applies {@code move} to {@code state}, returning the resulting (new) state. */
    S applyMove(S state, M move);

    /** Whether {@code state} is an ended game. */
    boolean isTerminal(S state);

    /** The winner when terminal; empty for a draw or a non-terminal state. */
    Optional<Player> winner(S state);
}
