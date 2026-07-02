package dev.kplanky.othello.matchmaking.dto;

import java.util.UUID;

/**
 * Result of {@code POST /api/matchmaking/queue} (spec §9/§15, M9.1). {@code status} is {@code QUEUED}
 * when the caller is now waiting for an opponent, or {@code MATCHED} when the join immediately paired
 * them with a waiting player — in which case {@code gameId} is the new {@code HUMAN_VS_HUMAN} game
 * ({@code null} when {@code QUEUED}). Both matched players also receive a {@code MATCH_FOUND} push on
 * their personal queue; this synchronous field just lets the joining caller learn the game id without
 * waiting on the socket.
 */
public record MatchmakingStatusResponse(String status, UUID gameId) {

    public static final String QUEUED = "QUEUED";
    public static final String MATCHED = "MATCHED";

    public static MatchmakingStatusResponse queued() {
        return new MatchmakingStatusResponse(QUEUED, null);
    }

    public static MatchmakingStatusResponse matched(UUID gameId) {
        return new MatchmakingStatusResponse(MATCHED, gameId);
    }
}
