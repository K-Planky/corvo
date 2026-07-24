package dev.kplanky.othello.game;

import dev.kplanky.othello.repository.GameRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The PvP turn-clock sweep (spec §15, M10): the server-authoritative check that forfeits a player who
 * lets their time bank run out on their turn. Scans the in-progress clocked PvP games and, for each one
 * whose side-to-move has expired, resolves the forfeit (a rated win for the opponent) and pushes
 * {@code GAME_OVER} over WebSocket, mirroring the {@link PvpMoveNotifier} push shape.
 *
 * <p>This holds the reusable logic and is always a bean; the {@code @Scheduled} trigger lives in
 * {@link TurnClockScheduler} (conditionally enabled) so the integration tests can drive {@link #sweep()}
 * deterministically without a background timer racing them.
 */
@Service
public class TurnClockService {

    private static final Logger log = LoggerFactory.getLogger(TurnClockService.class);

    private final GameRepository games;
    private final GameService gameService;
    private final GameEventPublisher publisher;

    public TurnClockService(GameRepository games, GameService gameService, GameEventPublisher publisher) {
        this.games = games;
        this.gameService = gameService;
        this.publisher = publisher;
    }

    /**
     * One pass over the active clocked PvP games: forfeit any whose side-to-move is out of time and
     * push the terminal state. Each game is resolved in its own transaction ({@link
     * GameService#forfeitExpiredTurn}); a game a concurrent move just advanced returns empty and is
     * skipped. Each game is guarded so one failure never aborts the rest of the tick: a move that won
     * the race with the forfeit write throws {@link OptimisticLockingFailureException} (benign, the
     * game simply continues), and any other error is logged and skipped so the sweep is self-healing.
     */
    public void sweep() {
        for (UUID gameId : games.findActiveClockedPvpGameIds()) {
            try {
                gameService.forfeitExpiredTurn(gameId)
                        .ifPresent(state -> publisher.gameOver(gameId, state));
            } catch (OptimisticLockingFailureException e) {
                // A move committed between the candidate scan and the forfeit write, resetting the
                // clock, no forfeit is owed. Expected under real PvP; the game continues untouched.
                log.debug("turn-clock forfeit for game {} lost a race with a move; skipping", gameId);
            } catch (RuntimeException e) {
                // Never let one game's failure stop the others from being checked this tick.
                log.warn("turn-clock sweep failed for game {}; skipping", gameId, e);
            }
        }
    }
}
