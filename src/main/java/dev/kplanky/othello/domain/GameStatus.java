package dev.kplanky.othello.domain;

/**
 * Lifecycle/outcome of a game (spec §5, Appendix A). {@code status} is the authoritative outcome;
 * {@code Game.winnerId} is set only to a winning <em>human</em> and is {@code null} when a bot wins
 * (see Appendix C, A1).
 */
public enum GameStatus {
    IN_PROGRESS,
    BLACK_WON,
    WHITE_WON,
    DRAW,
    ABANDONED
}
