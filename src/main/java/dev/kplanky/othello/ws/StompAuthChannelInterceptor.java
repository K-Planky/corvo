package dev.kplanky.othello.ws;

import dev.kplanky.othello.auth.JwtService;
import dev.kplanky.othello.auth.JwtService.JwtPrincipal;
import dev.kplanky.othello.repository.GameRepository;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP-level authentication and authorization (spec §9/§10), the WebSocket mirror of the REST
 * anti-cheat:
 *
 * <ul>
 *   <li><b>CONNECT</b>, the JWT rides in an {@code Authorization: Bearer <jwt>} STOMP header (the
 *       HTTP handshake itself is anonymous, see {@link WebSocketConfig}). A missing, malformed, or
 *       expired token fails the {@code CONNECT}, so an unauthenticated client never opens a session;
 *       a valid token pins a {@link StompPrincipal} (named by user id) to the session.
 *   <li><b>SUBSCRIBE</b>, only a participant may subscribe to {@code /topic/games/{id}}, so a user
 *       cannot eavesdrop on a game they are not in. Personal destinations ({@code /user/**}) are
 *       inherently scoped to the session principal and need no extra check.
 * </ul>
 *
 * <p>Throwing from {@code preSend} aborts the frame: Spring returns a STOMP {@code ERROR} and the
 * subscription (or connection) never takes effect.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String GAME_TOPIC_PREFIX = "/topic/games/";

    private final JwtService jwtService;
    private final GameRepository games;

    public StompAuthChannelInterceptor(JwtService jwtService, GameRepository games) {
        this.jwtService = jwtService;
        this.games = games;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            default -> {
                // Other frames carry the session principal already; nothing to gate here.
            }
        }
        return message;
    }

    /** Verify the JWT on CONNECT and pin the identity to the session. */
    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("Missing or malformed Authorization header on STOMP CONNECT");
        }
        try {
            JwtPrincipal principal = jwtService.parseToken(header.substring(BEARER_PREFIX.length()));
            accessor.setUser(new StompPrincipal(principal.userId(), principal.username()));
        } catch (Exception e) {
            // Malformed/expired/wrong-key token: reject the CONNECT (client receives an ERROR frame).
            throw new MessagingException("Invalid JWT on STOMP CONNECT", e);
        }
    }

    /** A game topic is subscribable only by a participant of that game. */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(GAME_TOPIC_PREFIX)) {
            return; // Not a game topic (e.g. /user/**), no per-game check needed.
        }
        UUID userId = currentUserId(accessor);
        UUID gameId;
        try {
            gameId = UUID.fromString(destination.substring(GAME_TOPIC_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new MessagingException("Malformed game topic destination: " + destination, e);
        }
        if (!games.isParticipant(gameId, userId)) {
            throw new MessagingException("Not a participant of game " + gameId);
        }
    }

    /** The session's authenticated user id, or reject if somehow unauthenticated. */
    private static UUID currentUserId(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal principal) {
            return principal.userId();
        }
        // CONNECT must precede SUBSCRIBE and always sets a StompPrincipal; this is a defensive guard.
        throw new MessagingException("Unauthenticated STOMP session");
    }
}
