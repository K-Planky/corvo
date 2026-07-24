package dev.kplanky.othello.leaderboard;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public leaderboard endpoint (spec §9). Auth is optional, anyone may read the top players, so
 * this route is permitted unauthenticated in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping
    public List<LeaderboardEntry> top() {
        return leaderboard.leaderboard();
    }
}
