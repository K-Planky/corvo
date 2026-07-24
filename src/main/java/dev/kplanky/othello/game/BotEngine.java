package dev.kplanky.othello.game;

import dev.kplanky.othello.config.BotProperties;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.engine.AlphaBetaSearch;
import dev.kplanky.othello.engine.EpsilonRandomSearch;
import dev.kplanky.othello.engine.Evaluator;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.GreedySearch;
import dev.kplanky.othello.engine.IterativeDeepeningSearch;
import dev.kplanky.othello.engine.MoveOrdering;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.DiscCountEvaluator;
import dev.kplanky.othello.engine.othello.OthelloEvaluator;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloMoveOrdering;
import dev.kplanky.othello.engine.othello.OthelloState;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps a game's {@link BotDifficulty} to the AI that plays it (spec §7). This is the single seam
 * where difficulty turns into engine behaviour, and each tier is a distinct playing <em>character</em>
 *, evaluator, depth, and blunder chance all vary, because time budget alone cannot make a fast
 * engine beatable:
 *
 * <ul>
 *   <li><b>Easy</b>, one greedy ply over {@link DiscCountEvaluator} (grabs the most flips, like a
 *       beginner), wrapped in {@link EpsilonRandomSearch} for an occasional random move.</li>
 *   <li><b>Medium</b>, fixed shallow {@link AlphaBetaSearch} over the phase-aware
 *       {@link OthelloEvaluator} (sees tactics, not deep strategy), with a small blunder chance.</li>
 *   <li><b>Hard</b>, depth-capped {@link IterativeDeepeningSearch} over the full evaluator with the
 *       static {@link OthelloMoveOrdering}; deterministic, strong, but bounded so a human who plans a
 *       few moves ahead can win.</li>
 * </ul>
 *
 * <p>All knobs come from {@link BotProperties}. The difficulty's fixed Elo label (1000/1500/1800)
 * also comes from here, recorded on the game at creation, display-only, since vs-AI is unrated
 * practice (spec §8).
 *
 * <p>A fresh search is built per move: none of the searches hold state across a call, so this is
 * cheap and keeps Hard's budget re-armed each time. On the synchronous path Hard's budget is clamped
 * to the synchronous-think cap (see {@link BotProperties}) so a slow search can't hold the HTTP
 * request open.
 */
@Component
public class BotEngine {

    private final GameRules<OthelloState, OthelloMove> rules;
    private final Evaluator<OthelloState> evaluator;
    private final Evaluator<OthelloState> discCount;
    private final MoveOrdering<OthelloState, OthelloMove> ordering;
    private final BotProperties properties;

    /** Randomness for the Easy/Medium blunder branch; {@code null} draws per call (thread-safe). */
    private final RandomGenerator rng;

    @Autowired // two constructors exist (the other is the test seam), tell Spring which one to use
    public BotEngine(GameRules<OthelloState, OthelloMove> rules, BotProperties properties) {
        this(rules, properties, null);
    }

    /** Test seam: a fixed {@link RandomGenerator} makes Easy/Medium move selection deterministic. */
    BotEngine(GameRules<OthelloState, OthelloMove> rules, BotProperties properties, RandomGenerator rng) {
        this.rules = rules;
        this.properties = properties;
        this.evaluator = new OthelloEvaluator();
        this.discCount = new DiscCountEvaluator();
        this.ordering = new OthelloMoveOrdering();
        this.rng = rng;
    }

    /** The move chooser for {@code difficulty} on the synchronous path (Hard's budget is capped). */
    public Search<OthelloState, OthelloMove> searchFor(BotDifficulty difficulty) {
        return searchWithHardBudget(difficulty, cappedHardBudget());
    }

    /**
     * The move chooser for the <em>asynchronous</em> reply (M8): Hard gets its full, uncapped budget.
     * Safe because the search runs off the request thread on the bounded worker pool and the move is
     * pushed over WebSocket, so a slow Hard search holds no HTTP request open. Easy and Medium are
     * identical on both paths, they don't take a time budget.
     */
    public Search<OthelloState, OthelloMove> asyncSearchFor(BotDifficulty difficulty) {
        return searchWithHardBudget(difficulty, properties.hard().budget());
    }

    private Search<OthelloState, OthelloMove> searchWithHardBudget(BotDifficulty difficulty, Duration hardBudget) {
        return switch (difficulty) {
            case EASY -> new EpsilonRandomSearch<>(
                    rules, new GreedySearch<>(rules, discCount), properties.easy().epsilon(), rng);
            case MEDIUM -> new EpsilonRandomSearch<>(
                    rules,
                    new AlphaBetaSearch<>(rules, evaluator, ordering, properties.medium().depth()),
                    properties.medium().epsilon(),
                    rng);
            case HARD -> new IterativeDeepeningSearch<>(
                    rules, evaluator, ordering, hardBudget, properties.hard().maxDepth());
        };
    }

    /** Hard's budget clamped to the synchronous-think cap (the M6/M8 request-thread gating rule). */
    private Duration cappedHardBudget() {
        Duration budget = properties.hard().budget();
        Duration cap = properties.syncThinkCap();
        return budget.compareTo(cap) <= 0 ? budget : cap;
    }

    /** The bot's fixed Elo label for {@code difficulty} (recorded on the game; vs-AI is unrated). */
    public int ratingFor(BotDifficulty difficulty) {
        return difficulty.rating();
    }
}
