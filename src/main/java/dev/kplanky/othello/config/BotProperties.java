package dev.kplanky.othello.config;

import dev.kplanky.othello.domain.BotDifficulty;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI think-time configuration (spec §7), bound from the {@code bot.*} properties. Each difficulty
 * maps to a per-move time budget for the iterative-deepening search: Easy ~0.4 s, Medium ~1.5 s,
 * Hard ~4 s (spec §7's 0.3–0.5 / 1–2 / 3–5 s bands).
 *
 * <p>{@code syncThinkCap} is the Milestone-6 gating valve: when the vs-AI reply is computed
 * <em>synchronously on the HTTP request thread</em> (creation's bot-opening move, and the move-submit
 * reply when {@code asyncReply} is off), a multi-second Hard search would hold the request open, so
 * the effective budget is clamped to this cap (default 1 s).
 *
 * <p>{@code asyncReply} (default {@code true}, M8) moves the move-submit AI reply off the request
 * thread onto a bounded worker pool and pushes it over WebSocket, so the cap no longer applies there
 * and the full Hard budget is restored. Turning it off reverts to the synchronous in-request reply
 * (used by the deterministic service tests, which don't want a background worker racing their reads).
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Duration easy, Duration medium, Duration hard, Duration syncThinkCap, Boolean asyncReply) {

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
        if (asyncReply == null) {
            asyncReply = Boolean.TRUE;
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
