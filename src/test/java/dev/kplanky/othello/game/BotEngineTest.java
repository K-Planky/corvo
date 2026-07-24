package dev.kplanky.othello.game;

import dev.kplanky.othello.config.BotProperties;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.engine.AlphaBetaSearch;
import dev.kplanky.othello.engine.EpsilonRandomSearch;
import dev.kplanky.othello.engine.GreedySearch;
import dev.kplanky.othello.engine.IterativeDeepeningSearch;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.DiscCountEvaluator;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Difficulty → engine mapping (spec §7). Pins each tier's distinct character, Easy is an
 * epsilon-random greedy, Medium a shallow alpha-beta with a blunder chance, Hard a depth-capped
 * iterative deepening, plus the fixed Elo labels, the tuning defaults, and the synchronous-think
 * cap that keeps a Hard search from blocking the request thread. All without Spring or a real
 * search run, so it is fast and deterministic.
 */
class BotEngineTest {

    private final OthelloRules rules = new OthelloRules();

    @Test
    void fixedBotRatingsMatchTheSpec() {
        BotEngine engine = engineWith(defaultProperties());
        assertThat(engine.ratingFor(BotDifficulty.EASY)).isEqualTo(1000);
        assertThat(engine.ratingFor(BotDifficulty.MEDIUM)).isEqualTo(1500);
        assertThat(engine.ratingFor(BotDifficulty.HARD)).isEqualTo(1800);
        // The rating is the enum's own constant, the engine just exposes it.
        assertThat(BotDifficulty.HARD.rating()).isEqualTo(1800);
    }

    @Test
    void easyIsAnEpsilonRandomGreedy() {
        BotEngine engine = engineWith(defaultProperties());
        for (var search : bothPaths(engine, BotDifficulty.EASY)) {
            EpsilonRandomSearch<OthelloState, OthelloMove> easy = asEpsilonRandom(search);
            assertThat(easy.epsilon()).isEqualTo(0.30);
            assertThat(easy.delegate()).isInstanceOf(GreedySearch.class);
            // The beginner character hinges on the *disc-count-only* heuristic, pin it, or a future
            // rewire to the full evaluator would silently strengthen Easy with every test green.
            assertThat(((GreedySearch<OthelloState, OthelloMove>) easy.delegate()).evaluator())
                    .isInstanceOf(DiscCountEvaluator.class);
        }
    }

    @Test
    void mediumIsAnEpsilonRandomShallowAlphaBeta() {
        BotEngine engine = engineWith(defaultProperties());
        for (var search : bothPaths(engine, BotDifficulty.MEDIUM)) {
            EpsilonRandomSearch<OthelloState, OthelloMove> medium = asEpsilonRandom(search);
            assertThat(medium.epsilon()).isEqualTo(0.10);
            assertThat(medium.delegate()).isInstanceOf(AlphaBetaSearch.class);
            assertThat(((AlphaBetaSearch<?, ?>) medium.delegate()).depth()).isEqualTo(3);
        }
    }

    @Test
    void hardIsDepthCappedIterativeDeepening() {
        BotEngine engine = engineWith(defaultProperties());
        for (var search : bothPaths(engine, BotDifficulty.HARD)) {
            assertThat(search).isInstanceOf(IterativeDeepeningSearch.class);
            assertThat(((IterativeDeepeningSearch<?, ?>) search).maxDepth()).isEqualTo(5);
        }
    }

    @Test
    void hardsBudgetIsClampedOnlyOnTheSynchronousPath() {
        // Budget above the cap: the in-request search is clamped so it can't hold the HTTP request
        // open; the M8 async worker gets the full budget.
        BotProperties properties = new BotProperties(
                null, null,
                new BotProperties.Hard(5, Duration.ofSeconds(4)),
                Duration.ofMillis(500), null);
        BotEngine engine = engineWith(properties);

        assertThat(budgetOf(engine.searchFor(BotDifficulty.HARD))).isEqualTo(Duration.ofMillis(500));
        assertThat(budgetOf(engine.asyncSearchFor(BotDifficulty.HARD))).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void aBudgetUnderTheCapIsUsedAsIsOnBothPaths() {
        BotEngine engine = engineWith(defaultProperties()); // 1200 ms budget, 60 s cap
        assertThat(budgetOf(engine.searchFor(BotDifficulty.HARD))).isEqualTo(Duration.ofMillis(1200));
        assertThat(budgetOf(engine.asyncSearchFor(BotDifficulty.HARD))).isEqualTo(Duration.ofMillis(1200));
    }

    @Test
    void defaultsAreTheSpecTuningAndAOneSecondCap() {
        BotProperties defaults = new BotProperties(null, null, null, null, null);
        assertThat(defaults.easy().epsilon()).isEqualTo(0.30);
        assertThat(defaults.medium().depth()).isEqualTo(3);
        assertThat(defaults.medium().epsilon()).isEqualTo(0.10);
        assertThat(defaults.hard().maxDepth()).isEqualTo(5);
        assertThat(defaults.hard().budget()).isEqualTo(Duration.ofMillis(1200));
        assertThat(defaults.syncThinkCap()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.asyncReply()).isTrue();
    }

    @Test
    void tuningKnobsAreValidatedAtBind() {
        assertThatThrownBy(() -> new BotProperties.Easy(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotProperties.Medium(0, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotProperties.Medium(3, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotProperties.Hard(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotProperties.Hard(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private BotEngine engineWith(BotProperties properties) {
        return new BotEngine(rules, properties);
    }

    /** Default tiers with a cap comfortably above Hard's budget, so no clamping unless a test asks. */
    private static BotProperties defaultProperties() {
        return new BotProperties(null, null, null, Duration.ofSeconds(60), null);
    }

    /** Both the synchronous and the M8 async move chooser, the tier's character must match on each. */
    private static List<Search<OthelloState, OthelloMove>> bothPaths(BotEngine engine, BotDifficulty difficulty) {
        return List.of(engine.searchFor(difficulty), engine.asyncSearchFor(difficulty));
    }

    @SuppressWarnings("unchecked")
    private static EpsilonRandomSearch<OthelloState, OthelloMove> asEpsilonRandom(
            Search<OthelloState, OthelloMove> search) {
        assertThat(search).isInstanceOf(EpsilonRandomSearch.class);
        return (EpsilonRandomSearch<OthelloState, OthelloMove>) search;
    }

    private static Duration budgetOf(Search<OthelloState, OthelloMove> search) {
        assertThat(search).isInstanceOf(IterativeDeepeningSearch.class);
        return ((IterativeDeepeningSearch<OthelloState, OthelloMove>) search).budget();
    }
}
