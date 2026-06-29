package dev.kplanky.othello.user.dto;

import dev.kplanky.othello.domain.RatingHistory;
import java.time.Instant;
import java.util.UUID;

/** One point on a user's rating timeline (spec §8/§9): the before/after ratings and the game it came from. */
public record RatingHistoryEntry(
        UUID gameId, int oldRating, int newRating, int delta, Instant createdAt) {

    static RatingHistoryEntry from(RatingHistory rh) {
        return new RatingHistoryEntry(
                rh.getGameId(), rh.getOldRating(), rh.getNewRating(), rh.getDelta(), rh.getCreatedAt());
    }
}
