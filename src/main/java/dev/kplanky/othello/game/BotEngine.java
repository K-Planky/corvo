package dev.kplanky.othello.game;

import dev.kplanky.othello.config.BotProperties;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.engine.Evaluator;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.IterativeDeepeningSearch;
import dev.kplanky.othello.engine.MoveOrdering;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.OthelloEvaluator;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloMoveOrdering;
import dev.kplanky.othello.engine.othello.OthelloState;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Maps a game's {@link BotDifficulty} to the AI that plays it (spec §7, M6.6). This is the single
 * seam where difficulty turns into engine behaviour: a difficulty selects a time budget, and that
 * budget drives an {@link IterativeDeepeningSearch} over the phase-aware {@link OthelloEvaluator}
 * with the static {@link OthelloMoveOrdering}. The difficulty's fixed Elo rating (1000/1500/1800)
 * also comes from here, recorded on the game at creation for M7's rating math.
 *
 * <p>A fresh search is built per move: {@link IterativeDeepeningSearch} holds no state across a call,
 * so this is cheap and keeps the budget re-armed each time. The budget is clamped to the
 * synchronous-think cap (see {@link BotProperties}) because the reply is still computed on the
 * request thread until Milestone 8.
 */
@Component
public class BotEngine {

    private final GameRules<OthelloState, OthelloMove> rules;
    private final Evaluator<OthelloState> evaluator;
    private final MoveOrdering<OthelloState, OthelloMove> ordering;
    private final BotProperties properties;

    public BotEngine(GameRules<OthelloState, OthelloMove> rules, BotProperties properties) {
        this.rules = rules;
        this.properties = properties;
        this.evaluator = new OthelloEvaluator();
        this.ordering = new OthelloMoveOrdering();
    }

    /** The move chooser for {@code difficulty}: iterative deepening within the (capped) time budget. */
    public Search<OthelloState, OthelloMove> searchFor(BotDifficulty difficulty) {
        return new IterativeDeepeningSearch<>(rules, evaluator, ordering, budgetFor(difficulty));
    }

    /**
     * The effective per-move budget for {@code difficulty}: the configured think-time, clamped to the
     * synchronous-think cap so a Hard search can't hold the HTTP request open for seconds before M8.
     */
    public Duration budgetFor(BotDifficulty difficulty) {
        Duration think = properties.thinkTime(difficulty);
        Duration cap = properties.syncThinkCap();
        return think.compareTo(cap) <= 0 ? think : cap;
    }

    /** The bot's fixed Elo rating for {@code difficulty} (recorded on the game for M7's Elo math). */
    public int ratingFor(BotDifficulty difficulty) {
        return difficulty.rating();
    }
}
