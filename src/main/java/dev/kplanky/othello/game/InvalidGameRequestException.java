package dev.kplanky.othello.game;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A malformed game-creation request (e.g. {@code botSide = NONE} for a vs-AI game). Maps to 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidGameRequestException extends RuntimeException {

    public InvalidGameRequestException(String message) {
        super(message);
    }
}
