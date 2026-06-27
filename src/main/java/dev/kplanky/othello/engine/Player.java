package dev.kplanky.othello.engine;

/**
 * The two sides in a two-player, zero-sum game. Othello's discs are black and white,
 * with Black to move first (see spec §6).
 */
public enum Player {
    BLACK,
    WHITE;

    /** The side whose turn it becomes after this player moves or passes. */
    public Player opponent() {
        return this == BLACK ? WHITE : BLACK;
    }
}
