package dev.kplanky.othello.game;

import dev.kplanky.othello.game.dto.GameStateResponse;
import java.util.UUID;

/**
 * The WebSocket push owed to a PvP mover's opponent after a committed move (M9.2), as planned by
 * {@link GameService#planPvpPush}. {@code opponentId} is the other participant; {@code opponentState}
 * is the new game view oriented to them (their legal moves now that the turn has flipped);
 * {@code terminal} is whether the move ended the game (⇒ push {@code GAME_OVER} instead of a
 * {@code YOUR_TURN} nudge).
 */
public record PvpMovePush(UUID opponentId, GameStateResponse opponentState, boolean terminal) {}
