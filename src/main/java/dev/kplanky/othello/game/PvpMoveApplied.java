package dev.kplanky.othello.game;

import java.util.UUID;

/**
 * Published inside {@link GameService#submitMove} once a human's move in a {@code HUMAN_VS_HUMAN} game
 * has been applied (spec §9/§15, M9.2). Consumed by {@link PvpMoveNotifier} <em>after the move
 * transaction commits</em>, so the pushed state carries the committed board (and any terminal Elo).
 * {@code moverId} is the submitter — the push goes to their opponent, who did not make this request.
 */
public record PvpMoveApplied(UUID gameId, UUID moverId) {}
