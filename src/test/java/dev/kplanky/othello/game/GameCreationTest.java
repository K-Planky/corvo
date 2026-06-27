package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M4.1 acceptance (spec §4/§9): {@code POST /api/games} creates a {@code HUMAN_VS_AI} game with the
 * human on the non-bot side and the board at the engine's initial position; when the bot plays Black
 * its opening move is already applied (and recorded), leaving the human (White) to move.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GameCreationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    UserRepository users;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        // Clear in FK order (moves → games → users): no ON DELETE CASCADE in the schema.
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
        JsonNode auth = objectMapper.readTree(mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"username\":\"player1\",\"email\":\"player1@example.com\",\"password\":\"correcthorse\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        this.token = auth.get("token").asText();
        this.userId = UUID.fromString(auth.get("user").get("id").asText());
    }

    @Test
    void createBotWhiteGameLeavesHumanAsBlackToMoveAtInitialPosition() throws Exception {
        String body = create("MEDIUM", "WHITE");
        JsonNode game = objectMapper.readTree(body);

        assertThat(game.get("opponentType").asText()).isEqualTo("HUMAN_VS_AI");
        assertThat(game.get("botSide").asText()).isEqualTo("WHITE");
        assertThat(game.get("botDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(game.get("currentTurn").asText()).isEqualTo("BLACK");
        assertThat(game.get("moveCount").asInt()).isEqualTo(0);
        // Human plays the non-bot side (Black); the bot side keeps a null player id.
        assertThat(game.get("blackPlayerId").asText()).isEqualTo(userId.toString());
        assertThat(game.get("whitePlayerId").isNull()).isTrue();
        assertThat(game.get("blackDiscs").asInt()).isEqualTo(2);
        assertThat(game.get("whiteDiscs").asInt()).isEqualTo(2);

        Game stored = games.findById(UUID.fromString(game.get("id").asText())).orElseThrow();
        OthelloState initial = OthelloState.initial();
        assertThat(stored.getBoardBlack()).isEqualTo(initial.black());
        assertThat(stored.getBoardWhite()).isEqualTo(initial.white());
        assertThat(stored.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(moves.findByGameIdOrderByMoveNumberAsc(stored.getId())).isEmpty();
    }

    @Test
    void createBotBlackGameAppliesAndRecordsTheOpeningMove() throws Exception {
        String body = create("EASY", "BLACK");
        JsonNode game = objectMapper.readTree(body);

        assertThat(game.get("botSide").asText()).isEqualTo("BLACK");
        // The bot (Black) has already moved, so it is the human's (White) turn.
        assertThat(game.get("currentTurn").asText()).isEqualTo("WHITE");
        assertThat(game.get("moveCount").asInt()).isEqualTo(1);
        assertThat(game.get("whitePlayerId").asText()).isEqualTo(userId.toString());
        assertThat(game.get("blackPlayerId").isNull()).isTrue();
        // One Othello move flips exactly one disc: black 2+1+1 = 4, white 2-1 = 1.
        assertThat(game.get("blackDiscs").asInt()).isEqualTo(4);
        assertThat(game.get("whiteDiscs").asInt()).isEqualTo(1);

        Game stored = games.findById(UUID.fromString(game.get("id").asText())).orElseThrow();
        OthelloState initial = OthelloState.initial();
        assertThat(stored.getBoardBlack()).isNotEqualTo(initial.black());

        List<Move> history = moves.findByGameIdOrderByMoveNumberAsc(stored.getId());
        assertThat(history).hasSize(1);
        Move opening = history.get(0);
        assertThat(opening.getMoveNumber()).isEqualTo(1);
        assertThat(opening.getPlayer()).isEqualTo(Player.BLACK);
        assertThat(opening.isPass()).isFalse();
        assertThat(opening.getPosition()).isNotNull();
        assertThat(Long.bitCount(opening.getFlippedMask())).isEqualTo(1);

        // Replaying the recorded opening from the initial position reproduces the stored board.
        OthelloState replayed = OthelloState.initial();
        assertThat(stored.getBoardBlack())
                .isEqualTo(replayed.black() | OthelloState.bit(opening.getPosition()) | opening.getFlippedMask());
    }

    @Test
    void createWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"EASY\",\"botSide\":\"WHITE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWithBotSideNoneIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"EASY\",\"botSide\":\"NONE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithMissingDifficultyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botSide\":\"WHITE\"}"))
                .andExpect(status().isBadRequest());
    }

    private String create(String difficulty, String botSide) throws Exception {
        return mockMvc.perform(post("/api/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"%s\",\"botSide\":\"%s\"}".formatted(difficulty, botSide)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
