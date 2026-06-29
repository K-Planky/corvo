package dev.kplanky.othello.repository;

/**
 * A single leaderboard row projected straight out of the Postgres window-function query (spec §8).
 * Spring Data maps the native query's column aliases to these getters by name — {@code rank} and
 * {@code percentile} are computed in SQL ({@code RANK()} / {@code PERCENT_RANK()}), not in app code.
 */
public interface LeaderboardRow {

    String getUsername();

    int getRating();

    /** Standard competition rank: 1 = top; ties share a rank and the next rank gaps (SQL {@code RANK()}). */
    long getRank();

    /** Percentile oriented higher = better: top ≈ 100, bottom ≈ 0 (the query inverts PERCENT_RANK). */
    double getPercentile();
}
