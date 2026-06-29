package dev.kplanky.othello.game;

import java.util.UUID;

/**
 * Published inside {@link GameService#submitMove} (async mode) once a human's move in a vs-AI game has
 * been applied, so the bot's reply is computed off the request thread (spec §9, M8). Consumed by
 * {@link AiReplyService} only <em>after the move transaction commits</em>, so the worker reads the
 * committed position. {@code humanId} is the submitter, used to orient the pushed state view.
 */
public record AiReplyRequested(UUID gameId, UUID humanId) {}
