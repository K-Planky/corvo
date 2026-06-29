package dev.kplanky.othello.game;

import dev.kplanky.othello.config.BotProperties;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.engine.IterativeDeepeningSearch;
import dev.kplanky.othello.engine.othello.OthelloRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Difficulty → engine mapping (spec §7, M6.6). Pins the fixed bot ratings, the per-difficulty
 * think-time budgets, and the synchronous-think cap that keeps a Hard search from blocking the
 * request thread before M8 — all without Spring or a real search, so it is fast and deterministic.
 */
class BotEngineTest {

    private final OthelloRules rules = new OthelloRules();

    @Test
    void fixedBotRatingsMatchTheSpec() {
        BotEngine engine = engineWith(uncappedProperties());
        assertThat(engine.ratingFor(BotDifficulty.EASY)).isEqualTo(1000);
        assertThat(engine.ratingFor(BotDifficulty.MEDIUM)).isEqualTo(1500);
        assertThat(engine.ratingFor(BotDifficulty.HARD)).isEqualTo(1800);
        // The rating is the enum's own constant — the engine just exposes it.
        assertThat(BotDifficulty.HARD.rating()).isEqualTo(1800);
    }

    @Test
    void budgetIsTheConfiguredThinkTimeWhenUnderTheCap() {
        BotEngine engine = engineWith(uncappedProperties());
        assertThat(engine.budgetFor(BotDifficulty.EASY)).isEqualTo(Duration.ofMillis(400));
        assertThat(engine.budgetFor(BotDifficulty.MEDIUM)).isEqualTo(Duration.ofMillis(1500));
        assertThat(engine.budgetFor(BotDifficulty.HARD)).isEqualTo(Duration.ofMillis(4000));
    }

    @Test
    void budgetIsClampedToTheSynchronousThinkCap() {
        // Cap below Medium/Hard but above Easy: Easy is untouched; the slower tiers are clamped so the
        // synchronous reply can't hold the request open for seconds (the M6/M8 gating rule).
        BotProperties capped = new BotProperties(
                Duration.ofMillis(400), Duration.ofMillis(1500), Duration.ofMillis(4000),
                Duration.ofMillis(500), null);
        BotEngine engine = engineWith(capped);
        assertThat(engine.budgetFor(BotDifficulty.EASY)).isEqualTo(Duration.ofMillis(400));
        assertThat(engine.budgetFor(BotDifficulty.MEDIUM)).isEqualTo(Duration.ofMillis(500));
        assertThat(engine.budgetFor(BotDifficulty.HARD)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void defaultsAreTheSpecBudgetsAndAOneSecondCap() {
        BotProperties defaults = new BotProperties(null, null, null, null, null);
        assertThat(defaults.thinkTime(BotDifficulty.EASY)).isEqualTo(Duration.ofMillis(400));
        assertThat(defaults.thinkTime(BotDifficulty.MEDIUM)).isEqualTo(Duration.ofMillis(1500));
        assertThat(defaults.thinkTime(BotDifficulty.HARD)).isEqualTo(Duration.ofMillis(4000));
        assertThat(defaults.syncThinkCap()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.asyncReply()).isTrue();
    }

    @Test
    void searchForEachDifficultyIsAnIterativeDeepeningSearch() {
        BotEngine engine = engineWith(uncappedProperties());
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            assertThat(engine.searchFor(difficulty)).isInstanceOf(IterativeDeepeningSearch.class);
        }
    }

    private BotEngine engineWith(BotProperties properties) {
        return new BotEngine(rules, properties);
    }

    /** Cap comfortably above every tier, so {@code budgetFor} returns the raw think-times. */
    private static BotProperties uncappedProperties() {
        return new BotProperties(
                Duration.ofMillis(400), Duration.ofMillis(1500), Duration.ofMillis(4000),
                Duration.ofSeconds(60), null);
    }
}
