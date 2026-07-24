package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The submitted move is not in the server-computed legal-move set, an illegal placement, or an
 * illegal pass (legal moves existed). Maps to 422, the third and final check of the anti-cheat
 * (spec §9/§10). Wraps the engine's {@link IllegalArgumentException} so the legality verdict is the
 * server's alone; the client never decides it.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class IllegalMoveException extends RuntimeException {

    public IllegalMoveException(UUID gameId, String reason) {
        super("illegal move in game " + gameId + ": " + reason);
    }
}
