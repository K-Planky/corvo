package dev.kplanky.othello.ws;

import dev.kplanky.othello.auth.JwtService;
import dev.kplanky.othello.auth.JwtService.JwtPrincipal;
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
 * Authenticates the STOMP {@code CONNECT} from the JWT and pins the resulting identity to the session
 * (spec §9/§10). The token rides in an {@code Authorization: Bearer <jwt>} STOMP header (the HTTP
 * handshake itself is anonymous — see {@link WebSocketConfig}); a missing, malformed, or expired
 * token makes the {@code CONNECT} fail, so an unauthenticated client never opens a session.
 *
 * <p>The principal set here ({@link StompPrincipal}, named by user id) flows to every later frame on
 * the session, which is what topic/queue authorization (M8.2) and per-user delivery (M8.4) build on.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
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
        return message;
    }
}
