package dev.kplanky.othello.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
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
 * M4.4 acceptance (spec §9): the REST surface drives a vs-AI game end to end —
 * create → get state (board/turn/caller's legal moves/status) → submit move → move history →
 * list my games — and returns the documented shapes. (Move authorization is M4.5.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GameRestApiTest {

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

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        moves.deleteAll();
        ratings.deleteAll();
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
    }

    @Test
    void createGetSubmitHistoryAndListDriveAVsAiGame() throws Exception {
        // Create: human plays Black (bot White), so it's the human's turn at the opening.
        String created = mockMvc.perform(authed(post("/api/games"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"MEDIUM\",\"botSide\":\"WHITE\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String gameId = objectMapper.readTree(created).get("id").asText();

        // Get state: board/turn/status plus the caller's four opening moves.
        String state = mockMvc.perform(authed(get("/api/games/" + gameId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTurn").value("BLACK"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.blackDiscs").value(2))
                // Render-ready board the thin client reads instead of the 64-bit bitboards: 64 chars,
                // the opening's four centre discs set (W at 27/36, B at 28/35).
                .andExpect(jsonPath("$.cells", hasLength(64)))
                .andExpect(jsonPath("$.cells").value(matchesPattern("\\.{27}WB\\.{6}BW\\.{27}")))
                .andExpect(jsonPath("$.legalMoves.length()").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();
        int firstLegal = objectMapper.readTree(state).get("legalMoves").get(0).asInt();

        // Submit the human's move; the bot replies synchronously, so it's the human's turn again.
        mockMvc.perform(authed(post("/api/games/" + gameId + "/moves"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":" + firstLegal + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moveCount").value(2))
                .andExpect(jsonPath("$.currentTurn").value("BLACK"));

        // Move history: the human's move then the bot's reply, in order.
        mockMvc.perform(authed(get("/api/games/" + gameId + "/moves")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].player").value("BLACK"))
                .andExpect(jsonPath("$[0].position").value(firstLegal))
                .andExpect(jsonPath("$[1].player").value("WHITE"));

        // List my in-progress games: the one we created is there.
        mockMvc.perform(authed(get("/api/games").param("status", "IN_PROGRESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(gameId));
    }

    @Test
    void getStateRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/games/" + java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void getUnknownGameIsNotFound() throws Exception {
        mockMvc.perform(authed(get("/api/games/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void listExcludesGamesWithOtherStatuses() throws Exception {
        mockMvc.perform(authed(post("/api/games"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"EASY\",\"botSide\":\"WHITE\"}"))
                .andExpect(status().isCreated());
        // No finished games yet, so filtering by a terminal status returns an empty list.
        mockMvc.perform(authed(get("/api/games").param("status", "BLACK_WON")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
