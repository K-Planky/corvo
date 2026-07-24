package dev.kplanky.othello.web;

import dev.kplanky.othello.auth.DuplicateRegistrationException;
import dev.kplanky.othello.auth.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Surfaces user-facing reasons for handled failures as a JSON {@link ApiError} body.
 *
 * <p>Auth exceptions carry deliberately user-safe, contextless copy (e.g. "invalid username or
 * password"), so the client should show it verbatim. Game/move exceptions are <em>not</em> handled
 * here on purpose: their messages embed internal game ids and read like debug text, so the client
 * maps the HTTP status to screen-appropriate copy instead.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(DuplicateRegistrationException.class)
    public ResponseEntity<ApiError> handleDuplicateRegistration(DuplicateRegistrationException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Bean-validation failures (@Valid request bodies), return the first field's reason. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldError();
        String message =
                first == null
                        ? "Some fields are invalid."
                        : first.getField() + " " + first.getDefaultMessage();
        return body(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), message));
    }
}
