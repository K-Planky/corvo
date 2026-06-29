package dev.kplanky.othello.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.GameService;
import dev.kplanky.othello.game.GameStateMapper;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import dev.kplanky.othello.user.dto.RatingHistoryEntry;
import dev.kplanky.othello.user.dto.UserStatsResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M7.3 acceptance (spec §9): {@code GET /api/users/{id}/stats} returns a user's W/L/D + current
 * rating + chronological {@code RatingHistory}, is a public read, and 404s for an unknown user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserStatsTest {

    @Autowired
    UserStatsService stats;

    @Autowired
    GameService gameService;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    UserRepository users;

    @Autowired
    GameRules<OthelloState, OthelloMove> rules;

    @Autowired
    GameStateMapper mapper;

    @Autowired
    MockMvc mockMvc;

    private UUID humanId;

    @BeforeEach
    void setUp() {
        ratings.deleteAll();
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "human@example.com", "hash")).getId();
    }

    @Test
    void returnsCountersRatingAndOrderedHistory() {
        // Two full vs-AI games → two terminal outcomes → two RatingHistory rows.
        playToTerminal();
        playToTerminal();

        UserStatsResponse s = stats.statsFor(humanId);
        User human = users.findById(humanId).orElseThrow();

        // Counters mirror the User row; exactly two games resolved.
        assertThat(s.gamesPlayed()).isEqualTo(2);
        assertThat(s.wins() + s.losses() + s.draws()).isEqualTo(2);
        assertThat(s.eloRating()).isEqualTo(human.getEloRating());
        assertThat(s.username()).isEqualTo("human");

        // Two rating points, oldest first and chained: the first starts at the seed rating (1200),
        // the second picks up where the first left off, and the latest equals the current rating.
        List<RatingHistoryEntry> history = s.ratingHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).oldRating()).isEqualTo(1200);
        assertThat(history.get(1).oldRating()).isEqualTo(history.get(0).newRating());
        assertThat(history.get(1).newRating()).isEqualTo(s.eloRating());
        assertThat(history).allSatisfy(
                e -> assertThat(e.delta()).isEqualTo(e.newRating() - e.oldRating()));
    }

    @Test
    void isPublicAndOmitsEmail() throws Exception {
        playToTerminal();

        mockMvc.perform(get("/api/users/{id}/stats", humanId)) // no Authorization header
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("human"))
                .andExpect(jsonPath("$.gamesPlayed").value(1))
                .andExpect(jsonPath("$.ratingHistory.length()").value(1))
                .andExpect(jsonPath("$.email").doesNotExist()); // PII not exposed on a public read
    }

    @Test
    void unknownUserIs404() throws Exception {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> stats.statsFor(missing)).isInstanceOf(UserNotFoundException.class);
        mockMvc.perform(get("/api/users/{id}/stats", missing)).andExpect(status().isNotFound());
    }

    /** Creates a vs-AI game (human Black) and plays both sides to a terminal via the apply pipeline. */
    private void playToTerminal() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
        Game game = games.findById(gameId).orElseThrow();
        int guard = 0;
        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
            gameService.applyMove(gameId, legal.isEmpty() ? OthelloMove.pass() : legal.get(0));
            game = games.findById(gameId).orElseThrow();
            assertThat(++guard).isLessThan(200);
        }
    }
}
