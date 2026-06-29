package dev.kplanky.othello.ws;

import java.security.Principal;
import java.util.UUID;

/**
 * The authenticated identity attached to a STOMP session by {@link StompAuthChannelInterceptor}
 * (spec §10). {@link #getName()} returns the user id so per-user destinations
 * ({@code /user/{userId}/queue/...}) route correctly; the username rides along for convenience.
 */
public record StompPrincipal(UUID userId, String username) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
