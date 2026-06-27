package dev.kplanky.othello.domain;

/**
 * AI difficulty for a vs-AI game (spec §5/§7, Appendix A). Each rung maps to a fixed Elo rating
 * used only for the human's Elo math (§8): {@code EASY = 1000}, {@code MEDIUM = 1500},
 * {@code HARD = 1800}. Bots are not a {@code User} row — difficulty is an enum on {@code Game}.
 */
public enum BotDifficulty {
    EASY,
    MEDIUM,
    HARD
}
