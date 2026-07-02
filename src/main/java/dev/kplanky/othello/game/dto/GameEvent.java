package dev.kplanky.othello.game.dto;

/**
 * A server→client WebSocket push (spec §9). {@code type} is the event kind — {@code MOVE_MADE} (a
 * move was applied), {@code GAME_OVER} (terminal), or {@code YOUR_TURN} (M8.4) on the per-game topic
 * {@code /topic/games/{id}}, or {@code MATCH_FOUND} (M9.1) on the recipient's personal queue — and
 * {@code state} is the full game view the client re-renders from, oriented to the recipient (their
 * legal moves, the outcome fields, final disc counts). For {@code MATCH_FOUND} the {@code state}
 * carries the newly created game (its {@code id} is the new {@code gameId}).
 */
public record GameEvent(String type, GameStateResponse state) {

    public static final String MOVE_MADE = "MOVE_MADE";
    public static final String GAME_OVER = "GAME_OVER";
    public static final String YOUR_TURN = "YOUR_TURN";
    public static final String MATCH_FOUND = "MATCH_FOUND";

    public static GameEvent moveMade(GameStateResponse state) {
        return new GameEvent(MOVE_MADE, state);
    }

    public static GameEvent gameOver(GameStateResponse state) {
        return new GameEvent(GAME_OVER, state);
    }

    public static GameEvent yourTurn(GameStateResponse state) {
        return new GameEvent(YOUR_TURN, state);
    }

    public static GameEvent matchFound(GameStateResponse state) {
        return new GameEvent(MATCH_FOUND, state);
    }
}
