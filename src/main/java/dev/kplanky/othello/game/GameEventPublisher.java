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
}
