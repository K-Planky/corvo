package dev.kplanky.othello.game.dto;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/games} for a vs-AI game (spec §9): the bot's {@code difficulty} and which
 * {@code botSide} it plays. {@code botSide} must be {@code BLACK} or {@code WHITE} — {@code NONE} is
 * rejected by the service. Phase 2's open-PvP variant gets its own request shape later.
 */
public record CreateGameRequest(
        @NotNull BotDifficulty difficulty, @NotNull BotSide botSide) {}
