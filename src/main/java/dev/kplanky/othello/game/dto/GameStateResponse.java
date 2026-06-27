package dev.kplanky.othello.game.dto;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.Player;
import java.util.List;
import java.util.UUID;

/**
 * Game state returned by the create/get/move endpoints (spec §9). Carries the denormalized board
 * (both bitboards plus disc counts), whose turn it is, the outcome fields, the vs-AI configuration,
 * and {@code legalMoves} — the square indices the <em>calling</em> user may play right now.
 *
 * <p>{@code legalMoves} is empty when it is not the caller's turn, when the caller is not a
 * participant, or when the game has ended. An empty list <em>on the caller's turn</em> (status
 * {@code IN_PROGRESS}, {@code currentTurn} == the caller's side) means they have no placement and
 * must pass — that is how the client decides to surface a Pass action.
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
        int whiteDiscs,
        List<Integer> legalMoves) {

    public static GameStateResponse of(Game game, List<Integer> legalMoves) {
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
                Long.bitCount(game.getBoardWhite()),
                List.copyOf(legalMoves));
    }
}
