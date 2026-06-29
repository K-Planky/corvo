package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;
import java.util.List;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Computes the vs-AI reply off the request thread and pushes it over WebSocket (spec §9, M8). Driven
 * by {@link AiReplyRequested}, handled <em>after</em> the human-move transaction commits and on the
 * bounded {@code botExecutor} pool, so the move POST returns immediately and a multi-second Hard
 * search never holds an HTTP request open.
 *
 * <p>The search runs outside any transaction (on the snapshot from {@link GameService#planBotReply});
 * only the short apply runs transactionally ({@link GameService#applyBotReply}). Terminal handling and
 * Elo are reused from {@link GameService}'s shared pipeline, so a game that ends on the bot's move (or
 * on the human's own move) is resolved identically to the synchronous path.
 */
@Service
public class AiReplyService {

    private final GameService gameService;
    private final BotEngine botEngine;
    private final GameRules<OthelloState, OthelloMove> rules;
    private final GameEventPublisher publisher;

    public AiReplyService(
            GameService gameService,
            BotEngine botEngine,
            GameRules<OthelloState, OthelloMove> rules,
            GameEventPublisher publisher) {
        this.gameService = gameService;
        this.botEngine = botEngine;
        this.rules = rules;
        this.publisher = publisher;
    }

    /**
     * Reacts to a committed human move: after commit (so the position is visible) the reply is computed
     * and pushed on the worker pool. {@code @TransactionalEventListener} defaults to the AFTER_COMMIT
     * phase; pairing it with {@code @Async} dispatches the handling to {@code botExecutor}.
     */
    @Async("botExecutor")
    @TransactionalEventListener
    public void onAiReplyRequested(AiReplyRequested event) {
        reply(event.gameId(), event.humanId());
    }

    /**
     * Resolves the pending reply for {@code gameId} as seen by {@code humanId}: pushes {@code GAME_OVER}
     * if the human's move ended the game; otherwise searches the bot's move, applies it through the
     * shared pipeline, and pushes {@code MOVE_MADE} (plus {@code GAME_OVER} if the reply was terminal).
     * Package-visible so tests can drive the reply deterministically without the executor.
     */
    void reply(java.util.UUID gameId, java.util.UUID humanId) {
        switch (gameService.planBotReply(gameId, humanId)) {
            case BotReplyPlan.GameOver(GameStateResponse state) -> publisher.gameOver(gameId, state);
            case BotReplyPlan.Reply(OthelloState state, var difficulty) -> {
                List<OthelloMove> legal = rules.getLegalMoves(state);
                OthelloMove move =
                        legal.isEmpty() ? OthelloMove.pass() : botEngine.asyncSearchFor(difficulty).bestMove(state);
                gameService.applyBotReply(gameId, move, humanId).ifPresent(after -> {
                    publisher.moveMade(gameId, after);
                    if (after.status() != GameStatus.IN_PROGRESS) {
                        publisher.gameOver(gameId, after);
                    }
                });
            }
            case BotReplyPlan.Nothing() -> {
                // Not a live vs-AI game on the bot's turn (e.g. a lost race) — nothing to push.
            }
        }
    }
}
