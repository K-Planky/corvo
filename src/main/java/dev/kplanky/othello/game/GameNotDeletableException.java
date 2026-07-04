package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The caller asked to delete a game that isn't eligible: a multiplayer match (rated and owned by an
 * opponent too — deleting it would wipe their live game) or a finished game (whose {@code rating_history}
 * must not be orphaned and whose Elo can't be un-applied). Deletion is limited to the caller's own
 * in-progress single-player games. Maps to 409 — the request conflicts with the game's state.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class GameNotDeletableException extends RuntimeException {

    public GameNotDeletableException(UUID gameId, String reason) {
        super("game " + gameId + " cannot be deleted: " + reason);
    }
}
