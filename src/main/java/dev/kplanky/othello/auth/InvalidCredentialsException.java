package dev.kplanky.othello.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a login presents an unknown username or a wrong password — maps to HTTP 401 (spec
 * §10). The message is deliberately generic so it can't be used to enumerate accounts.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid username or password");
    }
}
