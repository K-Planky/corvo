package dev.kplanky.othello.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI difficulty tuning (spec §7), bound from the {@code bot.*} properties. Each difficulty is a
 * distinct playing <em>character</em>, not just a bigger time budget — a fast engine is
 * near-unbeatable at any budget, so beatable tiers need weaker heuristics, shallower depth, and a
 * blunder chance:
 *
 * <ul>
 *   <li><b>Easy</b> — one greedy ply over disc count (plays like a beginner grabbing flips), plus an
 *       {@code epsilon} chance per move of playing a random legal move.</li>
 *   <li><b>Medium</b> — fixed-depth alpha-beta ({@code depth} plies) over the full phase-aware
 *       evaluator, plus a small {@code epsilon} blunder chance.</li>
 *   <li><b>Hard</b> — iterative deepening over the full evaluator, capped at {@code maxDepth} plies
 *       within a {@code budget} per move; deterministic.</li>
 * </ul>
 *
 * <p>{@code syncThinkCap} clamps Hard's budget whenever the vs-AI reply is computed
 * <em>synchronously on the HTTP request thread</em> (creation's bot-opening move, and the move-submit
 * reply when {@code asyncReply} is off), so a slow search can't hold the request open (default 1 s).
 * Easy and Medium don't take a time budget — a greedy ply and a depth-3 alpha-beta both return in
 * milliseconds — so the cap is moot for them.
 *
 * <p>{@code asyncReply} (default {@code true}, M8) moves the move-submit AI reply off the request
 * thread onto a bounded worker pool and pushes it over WebSocket, so the cap no longer applies there
 * and Hard's full budget is used. Turning it off reverts to the synchronous in-request reply (used by
 * the deterministic service tests, which don't want a background worker racing their reads).
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(Easy easy, Medium medium, Hard hard, Duration syncThinkCap, Boolean asyncReply) {

    /** Easy tier: greedy disc-grabbing with an {@code epsilon} chance of a random move. */
    public record Easy(Double epsilon) {
        public Easy {
            if (epsilon == null) {
                epsilon = 0.30;
            }
            requireUnitRange(epsilon, "bot.easy.epsilon");
        }
    }

    /** Medium tier: {@code depth}-ply alpha-beta with an {@code epsilon} blunder chance. */
    public record Medium(Integer depth, Double epsilon) {
        public Medium {
            if (depth == null) {
                depth = 3;
            }
            if (epsilon == null) {
                epsilon = 0.10;
            }
            requireAtLeastOne(depth, "bot.medium.depth");
            requireUnitRange(epsilon, "bot.medium.epsilon");
        }
    }

    /** Hard tier: iterative deepening capped at {@code maxDepth} plies within {@code budget}. */
    public record Hard(Integer maxDepth, Duration budget) {
        public Hard {
            if (maxDepth == null) {
                maxDepth = 5;
            }
            if (budget == null) {
                budget = Duration.ofMillis(1200);
            }
            requireAtLeastOne(maxDepth, "bot.hard.max-depth");
            if (budget.isZero() || budget.isNegative()) {
                throw new IllegalArgumentException("bot.hard.budget must be positive, was " + budget);
            }
        }
    }

    public BotProperties {
        if (easy == null) {
            easy = new Easy(null);
        }
        if (medium == null) {
            medium = new Medium(null, null);
        }
        if (hard == null) {
            hard = new Hard(null, null);
        }
        if (syncThinkCap == null) {
            syncThinkCap = Duration.ofSeconds(1);
        }
        if (asyncReply == null) {
            asyncReply = Boolean.TRUE;
        }
    }

    private static void requireUnitRange(double epsilon, String property) {
        if (Double.isNaN(epsilon) || epsilon < 0.0 || epsilon > 1.0) {
            throw new IllegalArgumentException(property + " must be within [0, 1], was " + epsilon);
        }
    }

    private static void requireAtLeastOne(int depth, String property) {
        if (depth < 1) {
            throw new IllegalArgumentException(property + " must be >= 1, was " + depth);
        }
    }
}
