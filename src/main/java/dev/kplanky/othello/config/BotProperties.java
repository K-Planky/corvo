package dev.kplanky.othello.config;

import dev.kplanky.othello.domain.BotDifficulty;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI think-time configuration (spec §7), bound from the {@code bot.*} properties. Each difficulty
 * maps to a per-move time budget for the iterative-deepening search: Easy ~0.4 s, Medium ~1.5 s,
 * Hard ~4 s (spec §7's 0.3–0.5 / 1–2 / 3–5 s bands).
 *
 * <p>{@code syncThinkCap} is the Milestone-6 gating valve: until Milestone 8 moves the AI reply
 * off-thread and pushes it over WebSocket, the vs-AI reply is computed <em>synchronously on the HTTP
 * request thread</em>, so a multi-second Hard search would hold the request open. The effective
 * budget is therefore clamped to this cap (default 1 s) so a deployed game never blocks for seconds.
 * Once M8's async push exists the cap is raised (or removed) to restore the full Hard budget.
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(Duration easy, Duration medium, Duration hard, Duration syncThinkCap) {

    public BotProperties {
        if (easy == null) {
            easy = Duration.ofMillis(400);
        }
        if (medium == null) {
            medium = Duration.ofMillis(1500);
        }
        if (hard == null) {
            hard = Duration.ofMillis(4000);
        }
        if (syncThinkCap == null) {
            syncThinkCap = Duration.ofSeconds(1);
        }
    }

    /** The configured (uncapped) think-time for {@code difficulty}. */
    public Duration thinkTime(BotDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> easy;
            case MEDIUM -> medium;
            case HARD -> hard;
        };
    }
}
