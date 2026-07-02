package dev.kplanky.othello.user.dto;

import dev.kplanky.othello.domain.RatingHistory;
import dev.kplanky.othello.domain.User;
import java.util.List;
import java.util.UUID;

/**
 * Public stats for a user (spec §9): current rating, W/L/D counters, and the ordered rating history.
 * A public, unauthenticated read; the outcome counters come from the user's denormalized W/L/D, so a
 * null {@code winnerId} on any game (a bot win, §5/Appendix C A1) is a non-issue here.
 */
public record UserStatsResponse(
        UUID id,
        String username,
        int eloRating,
        int gamesPlayed,
        int wins,
        int losses,
        int draws,
        List<RatingHistoryEntry> ratingHistory) {

    public static UserStatsResponse of(User user, List<RatingHistory> history) {
        return new UserStatsResponse(
                user.getId(),
                user.getUsername(),
                user.getEloRating(),
                user.getGamesPlayed(),
                user.getWins(),
                user.getLosses(),
                user.getDraws(),
                history.stream().map(RatingHistoryEntry::from).toList());
    }
}
