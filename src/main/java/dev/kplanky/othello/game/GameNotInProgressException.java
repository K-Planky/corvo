package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A move was submitted to a game that is not {@code IN_PROGRESS} (already ended or abandoned). Maps
 * to 409, the same status M4.5 uses for an out-of-turn move. Guarding here protects the W/L/D
 * counters from a double-resolve on a terminal game (spec §9/§11).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class GameNotInProgressException extends RuntimeException {

    public GameNotInProgressException(UUID gameId) {
        super("game " + gameId + " is not in progress");
    }
}
