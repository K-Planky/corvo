package dev.kplanky.othello.game.dto;

/**
 * A server→client WebSocket push on {@code /topic/games/{id}} (spec §9). {@code type} is the event
 * kind — {@code MOVE_MADE} (a move was applied), {@code GAME_OVER} (terminal), or {@code YOUR_TURN}
 * (M8.4) — and {@code state} is the full post-event game view the client re-renders from, oriented to
 * the recipient (their legal moves, the outcome fields, final disc counts).
 */
public record GameEvent(String type, GameStateResponse state) {

    public static final String MOVE_MADE = "MOVE_MADE";
    public static final String GAME_OVER = "GAME_OVER";

    public static GameEvent moveMade(GameStateResponse state) {
        return new GameEvent(MOVE_MADE, state);
    }

    public static GameEvent gameOver(GameStateResponse state) {
        return new GameEvent(GAME_OVER, state);
    }
}
