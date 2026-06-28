package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A participant submitted a move when it was not their turn. Maps to 409 — the second check of the
 * anti-cheat (spec §9/§10), the same status a move against a not-{@code IN_PROGRESS} game returns.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class NotYourTurnException extends RuntimeException {

    public NotYourTurnException(UUID gameId) {
        super("not the caller's turn in game " + gameId);
    }
}
