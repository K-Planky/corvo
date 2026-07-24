package dev.kplanky.othello.engine.othello;

import dev.kplanky.othello.engine.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OthelloStateTest {

    @Test
    void initialPositionMatchesSpecDiagram() {
        OthelloState s = OthelloState.initial();

        // Black at 28 (e4) and 35 (d5); White at 27 (d4) and 36 (e5), spec §6.
        assertThat(s.black()).isEqualTo((1L << 28) | (1L << 35));
        assertThat(s.white()).isEqualTo((1L << 27) | (1L << 36));

        // Black moves first, no passes yet.
        assertThat(s.toMove()).isEqualTo(Player.BLACK);
        assertThat(s.consecutivePasses()).isZero();
    }

    @Test
    void initialPositionHasFourCentreDiscsAndNothingElse() {
        OthelloState s = OthelloState.initial();

        assertThat(s.at(27)).contains(Player.WHITE); // d4
        assertThat(s.at(28)).contains(Player.BLACK); // e4
        assertThat(s.at(35)).contains(Player.BLACK); // d5
        assertThat(s.at(36)).contains(Player.WHITE); // e5

        assertThat(s.count(Player.BLACK)).isEqualTo(2);
        assertThat(s.count(Player.WHITE)).isEqualTo(2);
        assertThat(Long.bitCount(s.occupied())).isEqualTo(4);

        // Every square outside the centre four is empty.
        for (int sq = 0; sq < 64; sq++) {
            if (sq == 27 || sq == 28 || sq == 35 || sq == 36) {
                continue;
            }
            assertThat(s.at(sq)).as("square %d should be empty", sq).isEmpty();
        }
    }

    @Test
    void initialBoardRendersAsTheSpecDiagram() {
        OthelloState s = OthelloState.initial();

        // Render row-by-row (rank 1 = row 0 at the top), '.' empty, 'B' black, 'W' white.
        String[] expected = {
            "........",
            "........",
            "........",
            "...WB...", // rank 4: d4=W, e4=B
            "...BW...", // rank 5: d5=B, e5=W
            "........",
            "........",
            "........",
        };

        for (int row = 0; row < 8; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 8; col++) {
                line.append(s.at(row * 8 + col)
                        .map(p -> p == Player.BLACK ? 'B' : 'W')
                        .orElse('.'));
            }
            assertThat(line.toString()).as("row %d", row).isEqualTo(expected[row]);
        }
    }

    @Test
    void overlappingDiscsAreRejected() {
        assertThatThrownBy(() -> new OthelloState(0b11L, 0b01L, Player.BLACK, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bitRangeIsValidated() {
        assertThatThrownBy(() -> OthelloState.bit(64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OthelloState.bit(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opponentFlipsSides() {
        assertThat(Player.BLACK.opponent()).isEqualTo(Player.WHITE);
        assertThat(Player.WHITE.opponent()).isEqualTo(Player.BLACK);
    }
}
