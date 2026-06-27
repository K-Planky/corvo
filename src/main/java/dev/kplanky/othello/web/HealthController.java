package dev.kplanky.othello.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Infrastructure liveness probe. Deliberately served at {@code /health} (not under {@code /api}),
 * since it is an operational check for the reverse proxy / deploy pipeline rather than a domain
 * endpoint of the §9 API contract.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
