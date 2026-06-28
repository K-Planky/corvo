package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The caller is not seated on either side of the game they tried to move in. Maps to 403 — the first
 * check of the per-game/per-turn anti-cheat (spec §9/§10): participant before turn before legality.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class NotAGameParticipantException extends RuntimeException {

    public NotAGameParticipantException(UUID gameId) {
        super("caller is not a participant in game " + gameId);
    }
}
