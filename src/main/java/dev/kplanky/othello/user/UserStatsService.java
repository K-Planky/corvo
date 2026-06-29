package dev.kplanky.othello.user;

import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import dev.kplanky.othello.user.dto.UserStatsResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a user's public stats (spec §9): current rating + W/L/D counters from the {@link User}
 * row, plus the chronological {@code RatingHistory} series.
 */
@Service
public class UserStatsService {

    private final UserRepository users;
    private final RatingHistoryRepository ratings;

    public UserStatsService(UserRepository users, RatingHistoryRepository ratings) {
        this.users = users;
        this.ratings = ratings;
    }

    @Transactional(readOnly = true)
    public UserStatsResponse statsFor(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        return UserStatsResponse.of(user, ratings.findByUserIdOrderByCreatedAtAsc(userId));
    }
}
