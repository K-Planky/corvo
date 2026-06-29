package dev.kplanky.othello.leaderboard;

import dev.kplanky.othello.repository.LeaderboardRow;

/**
 * One row of the public leaderboard response (spec §8/§9): a player's rating with their SQL-computed
 * {@code rank} and {@code percentile} (higher = better). A stable API shape mapped from the native
 * query's {@link LeaderboardRow} projection.
 */
public record LeaderboardEntry(String username, int rating, long rank, double percentile) {

    static LeaderboardEntry from(LeaderboardRow row) {
        return new LeaderboardEntry(
                row.getUsername(), row.getRating(), row.getRank(), row.getPercentile());
    }
}
