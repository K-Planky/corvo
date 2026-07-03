package dev.kplanky.othello.ws;

import dev.kplanky.othello.game.DisconnectPolicyService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Bridges Spring's STOMP session lifecycle events to the presence registry and the disconnect policy
 * (spec §15, M11.2). A {@link SessionConnectedEvent} fires after {@link StompAuthChannelInterceptor}
 * has authenticated a {@code CONNECT} and pinned a {@link StompPrincipal}; a {@link
 * SessionDisconnectEvent} fires when a session's socket closes (a clean {@code DISCONNECT} or a dropped
 * transport). We track sessions per user and only invoke the policy on a real online/offline
 * <em>transition</em> — the first session in makes a user online (cancelling any grace timer they own),
 * the last session out makes them offline (arming the grace timer). Multiple tabs therefore don't
 * spuriously trip the policy.
 */
@Component
public class PresenceEventListener {

    private final PresenceRegistry presence;
    private final DisconnectPolicyService policy;

    public PresenceEventListener(PresenceRegistry presence, DisconnectPolicyService policy) {
        this.presence = presence;
        this.policy = policy;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        UUID userId = userId(event.getUser());
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (userId != null && sessionId != null && presence.connect(userId, sessionId)) {
            policy.onUserOnline(userId);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        UUID userId = userId(event.getUser());
        String sessionId = event.getSessionId();
        if (userId != null && sessionId != null && presence.disconnect(userId, sessionId)) {
            policy.onUserOffline(userId);
        }
    }

    /** The authenticated user id on the session, or {@code null} if it somehow lacks our principal. */
    private static UUID userId(Principal user) {
        return user instanceof StompPrincipal principal ? principal.userId() : null;
    }
}
