package dev.kplanky.othello.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M7.2 acceptance (spec §8/§9): the leaderboard is built by the Postgres window-function query —
 * correct {@code RANK} ordering (ties share a rank), {@code PERCENT_RANK} oriented higher = better
 * (not shipped inverted), filtered to {@code games_played > 0}, and readable without auth.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeaderboardTest {

    @Autowired
    LeaderboardService leaderboard;

    @Autowired
    UserRepository users;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        users.deleteAll();
    }

    private void seed(String name, int rating, int gamesPlayed) {
        User u = new User(name, name + "@example.com", "hash");
        u.setEloRating(rating);
        u.setGamesPlayed(gamesPlayed);
        users.save(u);
    }

    @Test
    void ranksByRatingWithTiesAndHigherIsBetterPercentile() {
        seed("alice", 1600, 5);
        seed("bob", 1500, 3);
        seed("carol", 1500, 2); // tie with bob
        seed("dave", 1400, 1);
        seed("erin", 2000, 0); // highest rating but no games — excluded by games_played > 0

        List<LeaderboardEntry> board = leaderboard.leaderboard();

        // games_played > 0 filter: erin is absent despite the top rating.
        assertThat(board).extracting(LeaderboardEntry::username).doesNotContain("erin").hasSize(4);

        // Ordered by rating DESC: alice first, dave last; the 1500 tie sits in the middle (its
        // internal order is unspecified by the query).
        assertThat(board.get(0).username()).isEqualTo("alice");
        assertThat(board.get(3).username()).isEqualTo("dave");
        assertThat(List.of(board.get(1).username(), board.get(2).username()))
                .containsExactlyInAnyOrder("bob", "carol");

        // RANK(): 1, 2, 2, 4 — the tie shares rank 2 and the next rank skips to 4.
        assertThat(board.get(0).rank()).isEqualTo(1);
        assertThat(board.get(1).rank()).isEqualTo(2);
        assertThat(board.get(2).rank()).isEqualTo(2);
        assertThat(board.get(3).rank()).isEqualTo(4);

        // Percentile oriented higher = better: top player ≈ 100, bottom ≈ 0 (not inverted).
        // (1 - PERCENT_RANK()) * 100 over 4 rows: rank 1 → 100, rank 2 → 66.67, rank 4 → 0.
        assertThat(board.get(0).percentile()).isCloseTo(100.0, within(1e-6));
        assertThat(board.get(1).percentile()).isCloseTo(200.0 / 3.0, within(1e-6));
        assertThat(board.get(3).percentile()).isCloseTo(0.0, within(1e-6));
        assertThat(board.get(0).percentile()).isGreaterThan(board.get(3).percentile());
    }

    @Test
    void cappedAtTopOneHundred() {
        // 101 eligible players: the query's LIMIT 100 must drop the lowest-rated one.
        for (int i = 0; i < 101; i++) {
            seed("p" + i, 1000 + i, 1); // distinct ratings 1000..1100
        }

        List<LeaderboardEntry> board = leaderboard.leaderboard();

        assertThat(board).hasSize(100);
        // Highest rating first, and the cut-off row is the 101st-best (rating 1001, not 1000).
        assertThat(board.get(0).rating()).isEqualTo(1100);
        assertThat(board.get(99).rating()).isEqualTo(1001);
    }

    @Test
    void endpointIsReadableWithoutAuth() throws Exception {
        seed("solo", 1300, 1);

        mockMvc.perform(get("/api/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("solo"))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].rating").value(1300))
                .andExpect(jsonPath("$[0].percentile").value(100.0));
    }
}
