package dev.kplanky.othello.web;

import dev.kplanky.othello.auth.JwtService.JwtPrincipal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A protected endpoint used to prove the JWT filter chain end-to-end (spec §10): it returns
 * {@code 200} with the authenticated identity when a valid {@code Bearer} token is present and
 * {@code 401} otherwise. Not under {@code /api/auth/**}, so it requires authentication.
 */
@RestController
@RequestMapping("/api/ping")
public class PingController {

    @GetMapping
    public Map<String, Object> ping(@AuthenticationPrincipal JwtPrincipal principal) {
        return Map.of("userId", principal.userId(), "username", principal.username());
    }
}
