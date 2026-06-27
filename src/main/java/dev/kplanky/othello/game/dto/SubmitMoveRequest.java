package dev.kplanky.othello.game.dto;

import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.game.InvalidGameRequestException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body of {@code POST /api/games/{id}/moves} (spec §9): either a placement on {@code position}
 * (0..63) or a pass ({@code pass = true}, no position). Exactly one form must be present.
 */
public record SubmitMoveRequest(@Min(0) @Max(63) Integer position, Boolean pass) {

    /** Converts to an engine move, rejecting an ambiguous or empty body with 400. */
    public OthelloMove toMove() {
        if (Boolean.TRUE.equals(pass)) {
            if (position != null) {
                throw new InvalidGameRequestException("a pass must not carry a position");
            }
            return OthelloMove.pass();
        }
        if (position == null) {
            throw new InvalidGameRequestException("position is required unless pass is true");
        }
        return OthelloMove.at(position);
    }
}
