package dev.kplanky.othello.game;

import java.util.UUID;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

/**
 * Pushes a PvP move to the mover's opponent over WebSocket (spec §9/§15, M9.2) — the PvP mirror of
 * {@link AiReplyService}, minus the search (there is no bot). Driven by {@link PvpMoveApplied},
 * handled <em>after</em> the move transaction commits so the pushed state is the committed board and
 * any terminal Elo is already applied.
 *
 * <p>The {@code MOVE_MADE} goes to the game topic (both participants are subscribed; only the side to
 * move renders the oriented legal moves). When the move ended the game a {@code GAME_OVER} follows;
 * otherwise a {@code YOUR_TURN} nudges the opponent's personal queue, since the turn is now theirs.
 */
@Service
public class PvpMoveNotifier {

    private final GameService gameService;
    private final GameEventPublisher publisher;

    public PvpMoveNotifier(GameService gameService, GameEventPublisher publisher) {
        this.gameService = gameService;
        this.publisher = publisher;
    }

    /** {@code @TransactionalEventListener} defaults to AFTER_COMMIT; the push runs synchronously there. */
    @TransactionalEventListener
    public void onPvpMove(PvpMoveApplied event) {
        notifyOpponent(event.gameId(), event.moverId());
    }

    /** Package-visible so tests can drive the push deterministically. */
    void notifyOpponent(UUID gameId, UUID moverId) {
        gameService.planPvpPush(gameId, moverId).ifPresent(push -> {
            publisher.moveMade(gameId, push.opponentState());
            if (push.terminal()) {
                publisher.gameOver(gameId, push.opponentState());
            } else {
                publisher.yourTurn(push.opponentId(), push.opponentState());
            }
        });
    }
}
