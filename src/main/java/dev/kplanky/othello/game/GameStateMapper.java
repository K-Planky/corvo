package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.springframework.stereotype.Component;

/**
 * Maps between the engine's immutable {@link OthelloState} and the denormalized board columns on the
 * {@link Game} entity (spec §5 board representation). The two bitboards plus the side to move are the
 * persisted position; {@code consecutivePasses} is <em>not</em> a column, so {@link #toState} takes
 * it as a parameter — the caller sources it (0 at creation; from the move tail when resuming, M4.2).
 */
@Component
public class GameStateMapper {

    /** Copies the position carried by {@code state} (board + side to move) onto {@code game}. */
    public void writeState(Game game, OthelloState state) {
        game.setBoardBlack(state.black());
        game.setBoardWhite(state.white());
        game.setCurrentTurn(state.toMove());
    }

    /** Rebuilds an engine state from {@code game}'s stored position and the given pass count. */
    public OthelloState toState(Game game, int consecutivePasses) {
        return new OthelloState(
                game.getBoardBlack(), game.getBoardWhite(), game.getCurrentTurn(), consecutivePasses);
    }
}
