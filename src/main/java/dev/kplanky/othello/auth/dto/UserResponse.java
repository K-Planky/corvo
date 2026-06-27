package dev.kplanky.othello.auth.dto;

import dev.kplanky.othello.domain.User;
import java.util.UUID;

/** Public view of a user — never exposes the password hash. */
public record UserResponse(UUID id, String username, String email, int eloRating) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getEloRating());
    }
}
