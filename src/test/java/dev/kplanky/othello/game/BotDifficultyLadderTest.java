package dev.kplanky.othello.game;

import dev.kplanky.othello.config.BotProperties;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strength-ordering guard for the difficulty ladder (spec §7): the tiers must actually get stronger,
 * or a future tuning tweak could silently invert Easy and Hard. Full games are played engine-vs-engine
 * through {@link BotEngine}'s real per-tier searches with a seeded {@code RandomGenerator}, so the
 * playoff is deterministic, no wall-clock flakiness (the one timed tier, Hard, gets a budget so
 * generous the clock can never fire; its depth cap is the terminator, and a capped depth-5 search
 * finishes in milliseconds).
 *
 * <p>This is deliberately a majority assertion, not all-games: the blunder epsilon exists precisely
 * so a weaker tier occasionally lands a lucky game.
 */
class BotDifficultyLadderTest {

    private final OthelloRules rules = new OthelloRules();

    @Test
    void mediumBeatsEasyInAClearMajority() {
        BotEngine engine = new BotEngine(rules, deterministicHardProperties(), new Random(20260705L));

        int mediumWins = 0;
        for (int game = 0; game < 8; game++) {
            boolean mediumIsBlack = game % 2 == 0;
            int mediumMargin = playOut(
                    engine.searchFor(BotDifficulty.MEDIUM),
                    engine.searchFor(BotDifficulty.EASY),
                    mediumIsBlack);
            if (mediumMargin > 0) {
                mediumWins++;
            }
        }
        assertThat(mediumWins).as("Medium won %d of 8 against Easy", mediumWins).isGreaterThanOrEqualTo(6);
    }

    @Test
    void hardBeatsEasyOnBothColors() {
        BotEngine engine = new BotEngine(rules, deterministicHardProperties(), new Random(20260706L));

        for (boolean hardIsBlack : new boolean[] {true, false}) {
            int hardMargin = playOut(
                    engine.searchFor(BotDifficulty.HARD),
                    engine.searchFor(BotDifficulty.EASY),
                    hardIsBlack);
            assertThat(hardMargin)
                    .as("Hard (as %s) finished with a positive disc margin", hardIsBlack ? "Black" : "White")
                    .isPositive();
        }
    }

    /**
     * Plays a full game between {@code a} and {@code b} (auto-passing a side with no legal move) and
     * returns {@code a}'s final disc margin, positive means {@code a} won.
     */
    private int playOut(Search<OthelloState, OthelloMove> a, Search<OthelloState, OthelloMove> b,
                        boolean aIsBlack) {
        OthelloState state = rules.initialState();
        int guard = 0;
        while (!rules.isTerminal(state)) {
            List<OthelloMove> legal = rules.getLegalMoves(state);
            if (legal.isEmpty()) {
                state = rules.pass(state);
                continue;
            }
            boolean blackToMove = rules.currentPlayer(state) == Player.BLACK;
            Search<OthelloState, OthelloMove> mover = blackToMove == aIsBlack ? a : b;
            state = rules.applyMove(state, mover.bestMove(state));
            assertThat(++guard).isLessThan(200);
        }
        Player aSide = aIsBlack ? Player.BLACK : Player.WHITE;
        return Long.bitCount(state.discs(aSide)) - Long.bitCount(state.discs(aSide.opponent()));
    }

    /**
     * Default tier tuning, but Hard's clock made so generous it can never fire mid-playoff: the
     * depth cap is Hard's real strength limiter and terminates every move in milliseconds, so with
     * the clock out of the picture Hard's play, and therefore the whole seeded playoff, is
     * deterministic. The sync-think cap is raised to match so it doesn't clamp the budget back down.
     */
    private static BotProperties deterministicHardProperties() {
        return new BotProperties(
                null, null,
                new BotProperties.Hard(null, Duration.ofMinutes(5)),
                Duration.ofMinutes(5), null);
    }
}
