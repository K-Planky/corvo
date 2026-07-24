package dev.kplanky.othello.engine;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Rung 1 of the Milestone-6 AI ladder: a {@link Search} that plays a uniformly random legal move.
 * It is the bot used by the single-player vertical slice (Milestone 4), both for the bot's opening
 * move at game creation and (M4.3) for its synchronous reply, and the baseline the stronger
 * negamax/alpha-beta/iterative-deepening rungs are measured against later.
 *
 * <p>Stateless and thread-safe. The default constructor draws from {@link ThreadLocalRandom} at call
 * time; pass an explicit {@link RandomGenerator} to make move selection deterministic in tests.
 *
 * @param <S> the game state type
 * @param <M> the move type
 */
public final class RandomBot<S, M> implements Search<S, M> {

    private final GameRules<S, M> rules;

    /** Fixed source of randomness, or {@code null} to draw from {@link ThreadLocalRandom} per call. */
    private final RandomGenerator rng;

    public RandomBot(GameRules<S, M> rules) {
        this(rules, null);
    }

    public RandomBot(GameRules<S, M> rules, RandomGenerator rng) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.rng = rng;
    }

    @Override
    public M bestMove(S state) {
        List<M> moves = rules.getLegalMoves(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("no legal move available, the caller must pass");
        }
        RandomGenerator source = rng != null ? rng : ThreadLocalRandom.current();
        return moves.get(source.nextInt(moves.size()));
    }
}
