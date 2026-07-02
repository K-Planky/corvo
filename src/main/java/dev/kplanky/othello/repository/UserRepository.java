package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for {@link User} (spec §5). Auth finders land in M3; leaderboard in M7. */
public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByUsername(String username);

    Optional<User> findByUsername(String username);

    /**
     * The leaderboard (spec §8): rank + percentile computed by Postgres window functions, not in app
     * code. Window functions aren't expressible in JPQL, so this is native SQL. {@code PERCENT_RANK()}
     * over {@code DESC} gives the top player 0.0 — the opposite of the "95th percentile = elite"
     * intuition — so it is inverted to {@code (1 - PERCENT_RANK()) * 100}, leaving the top player ≈ 100
     * (higher = better). Only players who have finished a game appear; capped at the top 100.
     */
    @Query(
            value =
                    """
                    SELECT username                                                   AS username,
                           elo_rating                                                 AS rating,
                           RANK()         OVER (ORDER BY elo_rating DESC)             AS rank,
                           (1 - PERCENT_RANK() OVER (ORDER BY elo_rating DESC)) * 100 AS percentile
                    FROM users
                    WHERE games_played > 0
                    ORDER BY elo_rating DESC
                    LIMIT 100
                    """,
            nativeQuery = true)
    List<LeaderboardRow> findLeaderboard();
}
