package dev.kplanky.othello.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a registration reuses an existing username or email — maps to HTTP 409 (spec §10). */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateRegistrationException extends RuntimeException {

    public DuplicateRegistrationException(String message) {
        super(message);
    }
}
