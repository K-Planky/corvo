package dev.kplanky.othello.game.dto;

import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.engine.Player;

/**
 * One move in the ordered history returned by {@code GET /api/games/{id}/moves} (spec §9). A
 * placement carries its {@code position} (0..63) and the {@code flippedMask} it captured; a pass has
 * a {@code null} position, {@code pass = true}, and a zero mask.
 */
public record MoveResponse(int moveNumber, Player player, Integer position, boolean pass, long flippedMask) {

    public static MoveResponse from(Move move) {
        Integer position = move.getPosition() == null ? null : move.getPosition().intValue();
        return new MoveResponse(
                move.getMoveNumber(), move.getPlayer(), position, move.isPass(), move.getFlippedMask());
    }
}
