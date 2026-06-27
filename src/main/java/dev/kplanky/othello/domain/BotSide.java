package dev.kplanky.othello.domain;

/**
 * Which side a bot plays in a game, or {@link #NONE} when there is no bot (a human-vs-human game,
 * spec §5, Appendix A). Kept distinct from {@link dev.kplanky.othello.engine.Player} because that
 * enum models the two playing sides only and has no "no bot" value.
 */
public enum BotSide {
    BLACK,
    WHITE,
    NONE
}
