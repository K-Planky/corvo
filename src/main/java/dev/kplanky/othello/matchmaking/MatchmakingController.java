package dev.kplanky.othello.matchmaking;

import dev.kplanky.othello.auth.JwtService.JwtPrincipal;
import dev.kplanky.othello.matchmaking.dto.MatchmakingStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matchmaking REST endpoints (spec §9/§15, M9.1). Both require a valid JWT (they fall through to
 * {@code anyRequest().authenticated()} in the security chain). Joining pairs the caller with a waiting
 * player when one is present, creating a {@code HUMAN_VS_HUMAN} game and pushing {@code MATCH_FOUND} to
 * both over WebSocket.
 */
@RestController
@RequestMapping("/api/matchmaking/queue")
public class MatchmakingController {

    private final MatchmakingService matchmakingService;

    public MatchmakingController(MatchmakingService matchmakingService) {
        this.matchmakingService = matchmakingService;
    }

    @PostMapping
    public MatchmakingStatusResponse join(@AuthenticationPrincipal JwtPrincipal principal) {
        return matchmakingService.join(principal.userId());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal JwtPrincipal principal) {
        matchmakingService.leave(principal.userId());
    }
}
