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
     * A participant's WebSocket dropped and a disconnect grace timer has started (spec §15, M11.2),
     * broadcast to the game topic so the present player can surface an "opponent disconnected" overlay.
     * {@code state} is the (unchanged) current position, the board did not move.
     */
    public void opponentDisconnected(UUID gameId, GameStateResponse state) {
        broker.convertAndSend(GAME_TOPIC + gameId, GameEvent.opponentDisconnected(state));
    }

    /**
     * A previously-disconnected participant returned within the grace period (spec §15, M11.2); the
     * game resumes untouched. Broadcast to the game topic so the present player can clear the overlay.
     */
    public void opponentReconnected(UUID gameId, GameStateResponse state) {
        broker.convertAndSend(GAME_TOPIC + gameId, GameEvent.opponentReconnected(state));
    }

    /**
     * A convenience nudge to {@code userId} that it is now their turn (spec §9), delivered on their
     * personal queue {@code /user/queue/notifications}. {@code state} is the same view as the topic
     * push, so the recipient can render from it directly. (Most useful for PvP, M9; in vs-AI the
     * topic's {@code MOVE_MADE} already conveys the turn flip.)
     */
    public void yourTurn(UUID userId, GameStateResponse state) {
        broker.convertAndSendToUser(userId.toString(), "/queue/notifications", GameEvent.yourTurn(state));
    }

    /**
     * Matchmaking paired {@code userId} into a new game (spec §9/§15, M9.1), delivered on their
     * personal queue {@code /user/queue/notifications}, the same routing as {@link #yourTurn}, so a
     * user not yet in any game (and thus not subscribed to a game topic) still receives it.
     * {@code state} is the new game oriented to the recipient; its {@code id} is the new {@code gameId}.
     */
    public void matchFound(UUID userId, GameStateResponse state) {
        broker.convertAndSendToUser(userId.toString(), "/queue/notifications", GameEvent.matchFound(state));
    }
}
