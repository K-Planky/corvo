package dev.kplanky.othello.game;

import dev.kplanky.othello.game.dto.GameEvent;
import dev.kplanky.othello.game.dto.GameStateResponse;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes game events to the per-game topic {@code /topic/games/{id}} (spec §9), which only
 * participants may subscribe to (authorized in {@code StompAuthChannelInterceptor}, M8.2). The
 * single place move/terminal pushes are sent, so REST and the async AI worker emit identical shapes.
 */
@Component
public class GameEventPublisher {

    private static final String GAME_TOPIC = "/topic/games/";

    private final SimpMessagingTemplate broker;

    public GameEventPublisher(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    /** A move (human or AI) was applied; {@code state} is the new position. */
    public void moveMade(UUID gameId, GameStateResponse state) {
        broker.convertAndSend(GAME_TOPIC + gameId, GameEvent.moveMade(state));
    }

    /** The game reached a terminal state; {@code state} carries the outcome + final disc counts. */
    public void gameOver(UUID gameId, GameStateResponse state) {
        broker.convertAndSend(GAME_TOPIC + gameId, GameEvent.gameOver(state));
    }

    /**
     * A convenience nudge to {@code userId} that it is now their turn (spec §9), delivered on their
     * personal queue {@code /user/queue/notifications}. {@code state} is the same view as the topic
     * push, so the recipient can render from it directly. (Most useful for PvP — M9; in vs-AI the
     * topic's {@code MOVE_MADE} already conveys the turn flip.)
     */
    public void yourTurn(UUID userId, GameStateResponse state) {
        broker.convertAndSendToUser(userId.toString(), "/queue/notifications", GameEvent.yourTurn(state));
    }
}
