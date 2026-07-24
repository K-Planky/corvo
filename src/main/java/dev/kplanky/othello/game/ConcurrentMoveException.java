package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Two move submissions raced on the same game and this one lost (§11): by flush time either the
 * {@code @Version} it read was stale (the game UPDATE matched 0 rows) or another submission had already
 * inserted the same {@code (game_id, move_number)} (the unique index, the second guard against a
 * double move). Maps to 409, the caller should retry against the fresh state. The losing transaction
 * rolls back, so the board is never left corrupted.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConcurrentMoveException extends RuntimeException {

    public ConcurrentMoveException(UUID gameId, Throwable cause) {
        super("concurrent move on game " + gameId + "; retry against the current state", cause);
    }
}
