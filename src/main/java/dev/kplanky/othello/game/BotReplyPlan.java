package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;

/**
 * What the async AI worker should do for a vs-AI game after a human move committed (M8). Computed by
 * {@link GameService#planBotReply} in a read-only transaction so the (potentially multi-second) search
 * in {@link AiReplyService} runs <em>outside</em> any transaction — no DB connection is held while the
 * bot thinks.
 */
public sealed interface BotReplyPlan
        permits BotReplyPlan.GameOver, BotReplyPlan.Reply, BotReplyPlan.Nothing {

    /** The human's move was itself terminal: push {@code GAME_OVER}, schedule no reply. */
    record GameOver(GameStateResponse state) implements BotReplyPlan {}

    /** It is the bot's turn: search {@code state} at {@code difficulty}, then apply + push the reply. */
    record Reply(OthelloState state, BotDifficulty difficulty) implements BotReplyPlan {}

    /** Nothing to do (not a live vs-AI game on the bot's turn) — e.g. a lost race. */
    record Nothing() implements BotReplyPlan {}
}
