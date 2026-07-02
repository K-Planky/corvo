package dev.kplanky.othello.matchmaking;

import dev.kplanky.othello.game.GameEventPublisher;
import dev.kplanky.othello.game.GameService;
import dev.kplanky.othello.matchmaking.dto.MatchmakingStatusResponse;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The PvP matchmaking queue (spec §9/§15, M9.1). A simple in-memory FIFO: the first two distinct
 * players to join are paired into a {@code HUMAN_VS_HUMAN} game and both receive a {@code MATCH_FOUND}
 * push on their personal queue.
 *
 * <p><b>Known limitation (accepted for v1):</b> the queue lives in this instance's heap and the STOMP
 * broker is the in-memory simple broker, so both assume a <b>single app instance</b>. Multi-instance
 * matchmaking would need a shared queue + a shared broker (Redis/RabbitMQ).
 *
 * <p>Rating-banded matching (pair within ±N Elo) is a possible small upgrade (spec §15); v1 is plain
 * FIFO.
 */
@Service
public class MatchmakingService {

    /** FIFO order + dedupe; all access is guarded by {@link #lock}. */
    private final Set<UUID> queue = new LinkedHashSet<>();

    private final Object lock = new Object();

    private final GameService gameService;
    private final GameEventPublisher events;

    public MatchmakingService(GameService gameService, GameEventPublisher events) {
        this.gameService = gameService;
        this.events = events;
    }

    /**
     * Adds {@code userId} to the queue, pairing them with the longest-waiting other player if one is
     * present. Idempotent: a caller already queued stays queued (so a double-join can never self-pair).
     * The game creation and the two {@code MATCH_FOUND} pushes happen <em>outside</em> the lock, so the
     * monitor is never held across a DB write.
     */
    public MatchmakingStatusResponse join(UUID userId) {
        UUID opponent;
        synchronized (lock) {
            if (queue.contains(userId)) {
                return MatchmakingStatusResponse.queued(); // already waiting — no self-pair
            }
            opponent = pollFirst();
            if (opponent == null) {
                queue.add(userId);
                return MatchmakingStatusResponse.queued();
            }
            // Paired: the opponent has been removed from the queue and this user was never added.
        }

        UUID gameId = gameService.createPvpGame(opponent, userId);
        // Each player gets a view oriented to them (their own legal moves); the game is committed by
        // createPvpGame (separate transaction) before these reads/pushes run.
        events.matchFound(opponent, gameService.getGameState(gameId, opponent));
        events.matchFound(userId, gameService.getGameState(gameId, userId));
        return MatchmakingStatusResponse.matched(gameId);
    }

    /** Removes {@code userId} from the queue if present; a no-op otherwise. */
    public void leave(UUID userId) {
        synchronized (lock) {
            queue.remove(userId);
        }
    }

    /**
     * Empties the queue. Package-private, for test isolation only: the queue is a process-lifetime
     * singleton, so tests sharing one Spring context must reset it between cases (production never
     * clears it — players leave individually via {@link #leave}).
     */
    void clear() {
        synchronized (lock) {
            queue.clear();
        }
    }

    /** Removes and returns the longest-waiting queued player, or {@code null} if the queue is empty. */
    private UUID pollFirst() {
        Iterator<UUID> it = queue.iterator();
        if (!it.hasNext()) {
            return null;
        }
        UUID first = it.next();
        it.remove();
        return first;
    }
}
