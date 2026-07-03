package dev.kplanky.othello.ws;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory WebSocket presence: which users currently hold at least one live STOMP session (spec §15,
 * M11.2). A user may have several sessions (multiple tabs/devices), so each user maps to the set of
 * their session ids; a user is "online" while that set is non-empty. {@link #connect}/{@link
 * #disconnect} report the <em>transitions</em> (first session in ⇒ came online, last session out ⇒
 * went offline) so the caller fires the disconnect policy exactly once per real online/offline change,
 * not once per tab.
 *
 * <p>Single-instance only, like the matchmaking queue and the simple broker (§15 caveat): presence
 * lives in this JVM. A multi-instance deployment would need a shared/distributed presence store.
 */
@Component
public class PresenceRegistry {

    private final ConcurrentHashMap<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    /** Records a new session for {@code userId}; returns true iff this made the user newly online. */
    public boolean connect(UUID userId, String sessionId) {
        boolean[] cameOnline = {false};
        sessionsByUser.compute(userId, (id, sessions) -> {
            if (sessions == null) {
                sessions = ConcurrentHashMap.newKeySet();
                cameOnline[0] = true;
            }
            sessions.add(sessionId);
            return sessions;
        });
        return cameOnline[0];
    }

    /** Drops a session for {@code userId}; returns true iff this made the user newly offline. */
    public boolean disconnect(UUID userId, String sessionId) {
        boolean[] wentOffline = {false};
        sessionsByUser.compute(userId, (id, sessions) -> {
            if (sessions == null) {
                return null;
            }
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                wentOffline[0] = true;
                return null; // drop the empty set so the map doesn't leak keys
            }
            return sessions;
        });
        return wentOffline[0];
    }

    /** Whether {@code userId} currently holds at least one live STOMP session. */
    public boolean isOnline(UUID userId) {
        return sessionsByUser.containsKey(userId);
    }
}
