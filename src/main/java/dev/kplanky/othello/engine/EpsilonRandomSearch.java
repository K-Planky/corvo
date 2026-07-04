package dev.kplanky.othello.engine;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Blunder decorator for the difficulty ladder (spec §7): with probability {@code epsilon} it plays a
 * uniformly random legal move, otherwise it delegates to the wrapped {@link Search}. The random
 * branch is what makes a beatable bot feel <em>human</em> rather than merely shallow — a fallible
 * opponent occasionally hands over an opportunity, and no two games repeat — while the delegate
 * defines the tier's baseline character (greedy for Easy, shallow alpha-beta for Medium).
 *
 * <p>Always returns a legal move: the random branch draws from {@link GameRules#getLegalMoves}, and
 * every delegate {@link Search} in the engine only ever returns a legal move. Like {@link RandomBot},
 * the default randomness source is {@link ThreadLocalRandom} at call time (stateless and
 * thread-safe); pass an explicit {@link RandomGenerator} to make selection deterministic in tests.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public final class EpsilonRandomSearch<S, M> implements Search<S, M> {

    private final GameRules<S, M> rules;
    private final Search<S, M> delegate;
    private final double epsilon;

    /** Fixed source of randomness, or {@code null} to draw from {@link ThreadLocalRandom} per call. */
    private final RandomGenerator rng;

    /**
     * @param epsilon probability of playing a random legal move instead of the delegate's choice;
     *                must be within {@code [0, 1]}. 0 always delegates; 1 is {@link RandomBot}.
     */
    public EpsilonRandomSearch(GameRules<S, M> rules, Search<S, M> delegate, double epsilon, RandomGenerator rng) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (Double.isNaN(epsilon) || epsilon < 0.0 || epsilon > 1.0) {
            throw new IllegalArgumentException("epsilon must be within [0, 1], was " + epsilon);
        }
        this.epsilon = epsilon;
        this.rng = rng;
    }

    /** The wrapped search that chooses the non-blunder moves. */
    public Search<S, M> delegate() {
        return delegate;
    }

    /** The probability of playing a random legal move instead of the delegate's choice. */
    public double epsilon() {
        return epsilon;
    }

    @Override
    public M bestMove(S state) {
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("no legal move available — the caller must pass");
        }
        RandomGenerator source = rng != null ? rng : ThreadLocalRandom.current();
        if (source.nextDouble() < epsilon) {
            return moves.get(source.nextInt(moves.size()));
        }
        return delegate.bestMove(state);
    }
}
