package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Evaluator;
import dev.kplanky.othello.engine.Player;

/**
 * Beginner's-eye Othello evaluation: the raw disc-count difference, nothing else. Maximising
 * immediate flips is exactly how a first-time player plays — and it is famously <em>bad</em>
 * strategy (early disc leads routinely flip; corners and mobility are what matter), which is the
 * point: the Easy bot searches one ply over this so it grabs discs, ignores corners, and walks into
 * X-square traps just like the newcomer it is meant to give a fair game to (spec §7's Easy tier).
 *
 * <p>Like {@link OthelloEvaluator} it is exactly antisymmetric —
 * {@code evaluate(s, BLACK) == -evaluate(s, WHITE)} — so it is safe under any negamax-family search
 * should a caller ever pair it with one.
 */
public final class DiscCountEvaluator implements Evaluator<OthelloState> {

    @Override
    public int evaluate(OthelloState state, Player perspective) {
        return Long.bitCount(state.discs(perspective)) - Long.bitCount(state.discs(perspective.opponent()));
    }
}
