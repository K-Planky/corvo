package dev.kplanky.othello.rating;

/**
 * Standard Elo rating arithmetic (spec §8). Pure: no persistence, no rounding surprises hidden in a
 * service, so it is trivially unit-tested and reused by both the vs-AI path (only the human's rating
 * moves; the bot rating is fixed) and the symmetric PvP update (M9).
 *
 * <pre>
 *   E_a = 1 / (1 + 10^((R_b − R_a) / 400))   // expected score
 *   R_a' = R_a + K * (S_a − E_a)             // S_a ∈ {1 win, 0.5 draw, 0 loss}
 * </pre>
 */
public final class Elo {

    /** K-factor: 32 for fast convergence with a small playerbase (spec §8 / Appendix C). */
    public static final int K = 32;

    /** Score for a win, feeds {@code S_a} in the update formula. */
    public static final double WIN = 1.0;

    /** Score for a draw. */
    public static final double DRAW = 0.5;

    /** Score for a loss. */
    public static final double LOSS = 0.0;

    private Elo() {}

    /** The expected score of a player rated {@code rating} facing an opponent rated {@code opponentRating}. */
    public static double expectedScore(int rating, int opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    /**
     * The new rating after scoring {@code score} (use {@link #WIN}/{@link #DRAW}/{@link #LOSS})
     * against {@code opponentRating}, rounded to the nearest integer. Beating a stronger opponent
     * gains more than beating a weaker one; the deltas of a symmetric pair sum to zero before
     * rounding.
     */
    public static int updatedRating(int rating, int opponentRating, double score) {
        return rating + (int) Math.round(K * (score - expectedScore(rating, opponentRating)));
    }
}
