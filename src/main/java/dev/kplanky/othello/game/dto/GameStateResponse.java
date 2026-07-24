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
 * and {@code legalMoves}, the square indices the <em>calling</em> user may play right now.
 *
 * <p>{@code legalMoves} is empty when it is not the caller's turn, when the caller is not a
 * participant, or when the game has ended. An empty list <em>on the caller's turn</em> (status
 * {@code IN_PROGRESS}, {@code currentTurn} == the caller's side) means they have no placement and
 * must pass, that is how the client decides to surface a Pass action.
 *
 * <p>{@code cells} is a render-ready view of the board: 64 chars indexed {@code row * 8 + col}
 * (matching {@code Move.position} and {@code legalMoves}), each {@code 'B'} / {@code 'W'} / {@code
 * '.'}. The raw {@code boardBlack}/{@code boardWhite} bitboards are kept for completeness, but a
 * full board sets bits past 2^53 (any disc on rank 8), so they can't be read losslessly as JSON
 * numbers in JavaScript, the client renders from {@code cells} instead and never touches the
 * bitboards.
 *
 * <p>{@code blackTimeRemainingMs}/{@code whiteTimeRemainingMs} are each side's live time bank in
 * milliseconds (spec §15, M10): the side to move counts down from now, the idle side is frozen.
 * Both are {@code null} for an unclocked (vs-AI) game, the client shows a clock only when present.
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
        String cells,
        Player currentTurn,
        GameStatus status,
        UUID winnerId,
        int moveCount,
        int blackDiscs,
        int whiteDiscs,
        List<Integer> legalMoves,
        Long blackTimeRemainingMs,
        Long whiteTimeRemainingMs) {

    /** Builds a state view with no turn-clock (used by unclocked vs-AI paths and any legacy caller). */
    public static GameStateResponse of(Game game, List<Integer> legalMoves) {
        return of(game, legalMoves, null, null);
    }

    public static GameStateResponse of(
            Game game, List<Integer> legalMoves, Long blackTimeRemainingMs, Long whiteTimeRemainingMs) {
        return new GameStateResponse(
                game.getId(),
                game.getOpponentType(),
                game.getBlackPlayerId(),
                game.getWhitePlayerId(),
                game.getBotSide(),
                game.getBotDifficulty(),
                game.getBoardBlack(),
                game.getBoardWhite(),
                renderCells(game.getBoardBlack(), game.getBoardWhite()),
                game.getCurrentTurn(),
                game.getStatus(),
                game.getWinnerId(),
                game.getMoveCount(),
                Long.bitCount(game.getBoardBlack()),
                Long.bitCount(game.getBoardWhite()),
                List.copyOf(legalMoves),
                blackTimeRemainingMs,
                whiteTimeRemainingMs);
    }

    /** Flattens the two bitboards into a 64-char {@code B}/{@code W}/{@code .} string, index = square. */
    private static String renderCells(long black, long white) {
        char[] cells = new char[64];
        for (int square = 0; square < 64; square++) {
            long mask = 1L << square;
            cells[square] = (black & mask) != 0 ? 'B' : (white & mask) != 0 ? 'W' : '.';
        }
        return new String(cells);
    }
}
