package dev.kplanky.othello.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M4.5 acceptance (spec §9/§10): the per-game/per-turn anti-cheat on {@code POST
 * /api/games/{id}/moves}, checked in order — participant (403) → turn / game live (409) → legality
 * (422). The server, never the client, decides each verdict.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GameMoveAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    UserRepository users;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String ownerToken;
    private String opponentToken;
    private String outsiderToken;
    private UUID ownerId;
    private UUID opponentId;

    @BeforeEach
    void setUp() throws Exception {
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();
        ownerToken = register("owner");
        opponentToken = register("opponent");
        outsiderToken = register("outsider");
        ownerId = users.findByUsername("owner").orElseThrow().getId();
        opponentId = users.findByUsername("opponent").orElseThrow().getId();
    }

    @Test
    void nonParticipantIsForbidden() throws Exception {
        // Owner plays Black (bot White), so it's the owner's turn at the opening; an outsider who is
        // not seated in this game may not move in it — rejected before any turn/legality check.
        String gameId = createVsAiGame();
        mockMvc.perform(authed(post("/api/games/" + gameId + "/moves"), outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":19}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void participantMovingOutOfTurnIsConflict() throws Exception {
        // A two-human game with Black to move; White (a participant) submitting is out of turn → 409,
        // independent of whether the square would have been legal.
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_HUMAN);
        game.setBlackPlayerId(ownerId);
        game.setWhitePlayerId(opponentId);
        OthelloState initial = OthelloState.initial();
        game.setBoardBlack(initial.black());
        game.setBoardWhite(initial.white());
        game.setCurrentTurn(Player.BLACK);
        UUID gameId = games.save(game).getId();

        mockMvc.perform(authed(post("/api/games/" + gameId + "/moves"), opponentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":19}"))
                .andExpect(status().isConflict());
    }

    @Test
    void moveOnFinishedGameIsConflict() throws Exception {
        // A participant moving on a game that is no longer IN_PROGRESS also gets 409 (§9).
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_HUMAN);
        game.setBlackPlayerId(ownerId);
        game.setWhitePlayerId(opponentId);
        OthelloState initial = OthelloState.initial();
        game.setBoardBlack(initial.black());
        game.setBoardWhite(initial.white());
        game.setCurrentTurn(Player.BLACK);
        game.setStatus(GameStatus.BLACK_WON);
        UUID gameId = games.save(game).getId();

        mockMvc.perform(authed(post("/api/games/" + gameId + "/moves"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":19}"))
                .andExpect(status().isConflict());
    }

    @Test
    void illegalPlacementIsUnprocessable() throws Exception {
        // Owner is Black to move at the opening; a1 (square 0) is empty and brackets nothing, so the
        // server-computed legal set excludes it → 422.
        String gameId = createVsAiGame();
        mockMvc.perform(authed(post("/api/games/" + gameId + "/moves"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void illegalPassWhenMovesExistIsUnprocessable() throws Exception {
        // The opening position always has legal moves, so a pass is an illegal pass → 422.
        String gameId = createVsAiGame();
        mockMvc.perform(authed(post("/api/games/" + gameId + "/moves"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":true}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String createVsAiGame() throws Exception {
        String created = mockMvc.perform(authed(post("/api/games"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"EASY\",\"botSide\":\"WHITE\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String register(String username) throws Exception {
        JsonNode auth = objectMapper.readTree(mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"correcthorse\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        return auth.get("token").asText();
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
