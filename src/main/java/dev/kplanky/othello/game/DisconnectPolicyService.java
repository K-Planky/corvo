package dev.kplanky.othello.game;

import dev.kplanky.othello.config.PvpDisconnectProperties;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.ws.PresenceRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The opponent-disconnect policy (spec §15, M11.2), the "underestimated trap" of PvP. Because the
 * board is server-authoritative in Postgres, a reconnecting client just re-fetches state (M11.1); the
 * real design work is what to do while a player is <em>gone</em>:
 *
 * <ul>
 *   <li>On a participant going offline ({@link #onUserOffline}), a grace timer of {@code
 *       pvp.disconnect.grace} is armed for each of their in-progress PvP games and {@code
 *       OPPONENT_DISCONNECTED} is pushed so the present player sees why the game paused.
 *   <li>If they return within grace ({@link #onUserOnline}), the timer is cancelled and {@code
 *       OPPONENT_RECONNECTED} is pushed — the game was never touched, so play simply resumes.
 *   <li>If grace lapses ({@link #sweep}), the absent player is <b>forfeited</b> — a rated win for the
 *       present player (the documented policy), reusing the same {@link GameService#forfeit} path as a
 *       turn-clock timeout so a player cannot escape a losing position by pulling the plug.
 * </ul>
 *
 * <p>Grace does <em>not</em> pause the turn clock (M10) — the clock is server-authoritative like
 * everything else. If the absent player is on-turn, the clock sweep may forfeit them on time before
 * grace lapses; whichever fires first ends the game and the other sweep then sees a non-{@code
 * IN_PROGRESS} status and skips. Both resolutions are a rated loss for the offender, so the outcome is
 * consistent either way.
 *
 * <p>This holds the reusable logic and is always a bean; the {@code @Scheduled} trigger lives in
 * {@link DisconnectScheduler} (conditionally enabled) so tests drive {@link #sweep()} deterministically
 * — the same split as the turn-clock sweep. In-memory pending state is single-instance only (§15).
 */
@Service
public class DisconnectPolicyService {

    private static final Logger log = LoggerFactory.getLogger(DisconnectPolicyService.class);

    private final GameRepository games;
    private final GameService gameService;
    private final GameEventPublisher publisher;
    private final PresenceRegistry presence;
    private final PvpDisconnectProperties properties;

    /** gameId → the armed forfeit (who is absent, and when their grace lapses). */
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(UUID userId, Instant deadline) {}

    public DisconnectPolicyService(
            GameRepository games,
            GameService gameService,
            GameEventPublisher publisher,
            PresenceRegistry presence,
            PvpDisconnectProperties properties) {
        this.games = games;
        this.gameService = gameService;
        this.publisher = publisher;
        this.presence = presence;
        this.properties = properties;
    }

    /**
     * A participant went offline: arm a grace timer for each of their in-progress PvP games.
     *
     * <p>Known limitation (a documented policy corner, not covered by the Done-when): if <em>both</em>
     * players of a game disconnect, the second {@code put} overwrites the first, so the last to leave is
     * the armed offender and the (also-absent) opponent is credited the rated win on lapse. This
     * self-heals (the game ends, nothing leaks) and is consistent with the M10 turn clock, which
     * likewise forfeits an on-turn absent player toward an absent opponent. A future refinement could
     * withhold the forfeit until the beneficiary is actually present.
     */
    public void onUserOffline(UUID userId) {
        Instant deadline = Instant.now().plusMillis(properties.graceMs());
        for (UUID gameId : games.findActivePvpGameIdsForUser(userId)) {
            pending.put(gameId, new Pending(userId, deadline));
            liveState(gameId).ifPresent(state -> publisher.opponentDisconnected(gameId, state));
        }
    }

    /** A participant returned: cancel any grace timer they own and resume those games untouched. */
    public void onUserOnline(UUID userId) {
        pending.forEach((gameId, p) -> {
            if (p.userId().equals(userId) && pending.remove(gameId, p)) {
                liveState(gameId).ifPresent(state -> publisher.opponentReconnected(gameId, state));
            }
        });
    }

    /**
     * One pass over the armed grace timers: forfeit any whose deadline has lapsed while its player is
     * still offline, and push the terminal state. Each game is claimed with an atomic {@code remove} so
     * a concurrent {@link #onUserOnline} or another tick can't double-resolve it, and guarded so one
     * game's failure never aborts the rest — a move that won the race with the forfeit write throws
     * {@link OptimisticLockingFailureException} out of {@link GameService#forfeitDisconnected} (benign:
     * the game continues) and any other error is logged and skipped.
     */
    public void sweep() {
        sweep(Instant.now());
    }

    /**
     * The sweep against an explicit {@code now}, so tests can drive a lapse deterministically by passing
     * a time past the armed deadline — no sleeping — mirroring how the turn-clock tests backdate {@code
     * turnStartedAt}. Production always calls {@link #sweep()} with the real clock.
     */
    void sweep(Instant now) {
        pending.forEach((gameId, p) -> {
            if (now.isBefore(p.deadline()) || !pending.remove(gameId, p)) {
                return; // still within grace, or another thread already claimed this one
            }
            if (presence.isOnline(p.userId())) {
                // The player returned in the narrow window between our claim (the remove above) and
                // this check. onUserOnline may have found the entry already gone and pushed nothing, so
                // clear the present player's "opponent disconnected" overlay here instead — symmetric
                // with onUserOnline, and exactly-once (only one path wins the atomic remove).
                liveState(gameId).ifPresent(state -> publisher.opponentReconnected(gameId, state));
                return;
            }
            try {
                gameService.forfeitDisconnected(gameId, p.userId())
                        .ifPresent(state -> publisher.gameOver(gameId, state));
            } catch (OptimisticLockingFailureException e) {
                // A move committed between arming the timer and the forfeit write — no forfeit is owed.
                log.debug("disconnect forfeit for game {} lost a race with a move; skipping", gameId);
            } catch (RuntimeException e) {
                // Never let one game's failure stop the others from being resolved this tick.
                log.warn("disconnect sweep failed for game {}; skipping", gameId, e);
            }
        });
    }

    /** Current state of {@code gameId} if it is still live — the view carried by a presence push. */
    private Optional<GameStateResponse> liveState(UUID gameId) {
        try {
            GameStateResponse state = gameService.getGameState(gameId, null);
            return state.status() == GameStatus.IN_PROGRESS ? Optional.of(state) : Optional.empty();
        } catch (GameNotFoundException e) {
            return Optional.empty();
        }
    }
}
