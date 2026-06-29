package dev.kplanky.othello.leaderboard;

import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the leaderboard (spec §8/§9). The ranking work lives in Postgres window functions (see
 * {@link UserRepository#findLeaderboard()}); this just maps the projection to the API shape.
 */
@Service
public class LeaderboardService {

    private final UserRepository users;

    public LeaderboardService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntry> leaderboard() {
        return users.findLeaderboard().stream().map(LeaderboardEntry::from).toList();
    }
}
