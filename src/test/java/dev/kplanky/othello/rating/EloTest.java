package dev.kplanky.othello.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure Elo arithmetic (spec §8). Covers the expected-score curve and the
 * win/draw/loss deltas, including the asymmetry that makes the rating meaningful: a favourite gains
 * little for an expected win but pays heavily for an upset loss.
 */
class EloTest {

    @Test
    void equalRatingsExpectHalf() {
        assertThat(Elo.expectedScore(1500, 1500)).isEqualTo(0.5);
    }

    @Test
    void equalRatingsMoveByHalfKEachWay() {
        // K = 32, expected = 0.5 ⇒ win +16, draw 0, loss −16.
        assertThat(Elo.updatedRating(1500, 1500, Elo.WIN)).isEqualTo(1516);
        assertThat(Elo.updatedRating(1500, 1500, Elo.DRAW)).isEqualTo(1500);
        assertThat(Elo.updatedRating(1500, 1500, Elo.LOSS)).isEqualTo(1484);
    }

    @Test
    void favouriteGainsLittleForExpectedWinAndPaysForUpset() {
        // 1200 vs 1000: expected ≈ 0.760 ⇒ a win gains ~8, a loss costs ~24.
        assertThat(Elo.updatedRating(1200, 1000, Elo.WIN)).isEqualTo(1208);
        assertThat(Elo.updatedRating(1200, 1000, Elo.LOSS)).isEqualTo(1176);
    }

    @Test
    void underdogDrawAgainstAStrongerOpponentStillGainsRating() {
        // 1200 vs 1500: expected ≈ 0.151 ⇒ even a draw is above expectation (+11).
        assertThat(Elo.updatedRating(1200, 1500, Elo.DRAW)).isEqualTo(1211);
    }

    @Test
    void expectedScoresOfAPairAreComplementary() {
        assertThat(Elo.expectedScore(1200, 1000) + Elo.expectedScore(1000, 1200))
                .isCloseTo(1.0, within(1e-9));
    }
}
