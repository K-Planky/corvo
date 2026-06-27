package dev.kplanky.othello.game.dto;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.Player;
import java.util.UUID;

/**
 * Game state returned by the create/get endpoints (spec §9). Carries the denormalized board (both
 * bitboards plus disc counts), whose turn it is, the outcome fields, and the vs-AI configuration.
 * The caller's legal-move set is added by the {@code GET /api/games/{id}} endpoint in M4.4.
 */
public record GameStateResponse(
        UUID id,
        OpponentType opponentType,
        UUID blackPlayerId,
        UUID whitePlayerId,
        BotSide botSide,
        BotDifficulty botDifficulty,
        long boardBlack,
        long boardWhite,
        Player currentTurn,
        GameStatus status,
        UUID winnerId,
        int moveCount,
        int blackDiscs,
        int whiteDiscs) {

    public static GameStateResponse from(Game game) {
        return new GameStateResponse(
                game.getId(),
                game.getOpponentType(),
                game.getBlackPlayerId(),
                game.getWhitePlayerId(),
                game.getBotSide(),
                game.getBotDifficulty(),
                game.getBoardBlack(),
                game.getBoardWhite(),
                game.getCurrentTurn(),
                game.getStatus(),
                game.getWinnerId(),
                game.getMoveCount(),
                Long.bitCount(game.getBoardBlack()),
                Long.bitCount(game.getBoardWhite()));
    }
}
