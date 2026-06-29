package dev.kplanky.othello.user;

import dev.kplanky.othello.user.dto.UserStatsResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public user endpoints (spec §9). {@code GET /api/users/{id}/stats} is a public read (auth
 * optional), so this route is permitted unauthenticated in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserStatsService stats;

    public UserController(UserStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/{id}/stats")
    public UserStatsResponse stats(@PathVariable UUID id) {
        return stats.statsFor(id);
    }
}
