package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.springframework.stereotype.Component;

/**
 * Maps between the engine's immutable {@link OthelloState} and the denormalized board columns on the
 * {@link Game} entity (spec §5 board representation). The two bitboards plus the side to move are the
 * persisted position. The {@code consecutive_passes} column (V3) completes the snapshot, so the
 * mapping is a clean round-trip in both directions with no out-of-band state.
 */
@Component
public class GameStateMapper {

    /** Copies the position carried by {@code state} (board, side to move, pass count) onto {@code game}. */
    public void writeState(Game game, OthelloState state) {
        game.setBoardBlack(state.black());
        game.setBoardWhite(state.white());
        game.setCurrentTurn(state.toMove());
        game.setConsecutivePasses(state.consecutivePasses());
    }

    /** Rebuilds the engine state from {@code game}'s stored snapshot. */
    public OthelloState toState(Game game) {
        return new OthelloState(
                game.getBoardBlack(),
                game.getBoardWhite(),
                game.getCurrentTurn(),
                game.getConsecutivePasses());
    }
}
