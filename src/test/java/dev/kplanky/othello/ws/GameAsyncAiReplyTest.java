package dev.kplanky.othello.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.GameStateMapper;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * M8.3 acceptance (spec §9): the move POST returns immediately and the AI reply is computed off the
 * request thread and pushed over WebSocket as {@code MOVE_MADE}; when the human's own move is terminal
 * a {@code GAME_OVER} is pushed and no AI reply is scheduled.
 *
 * <p>Opts into the production async path ({@code bot.async-reply=true}) — the rest of the suite runs
 * the reply synchronously (see the Surefire config). {@code bot.hard} is shrunk so a "Hard" search
 * exercises the async path without a multi-second test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"bot.async-reply=true", "bot.hard=600ms"})
class GameAsyncAiReplyTest {

    @LocalServerPort
    int port;

    @Autowired
    GameRepository gamesRepo;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketStompClient stompClient;
    private String token;
    private UUID humanId;

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        ratings.deleteAll();
        gamesRepo.deleteAll();
        users.deleteAll();

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        JsonNode auth = post("/api/auth/register", null,
                "{\"username\":\"human\",\"password\":\"correcthorse\"}");
        token = auth.get("token").asText();
        humanId = UUID.fromString(auth.get("user").get("id").asText());
    }

    @Test
    void movePostReturnsImmediatelyAndBotReplyArrivesOverWebSocket() throws Exception {
        // Human plays Black (bot White, Hard), so it's the human's turn at the opening.
        JsonNode created = post("/api/games", token, "{\"difficulty\":\"HARD\",\"botSide\":\"WHITE\"}");
        UUID gameId = UUID.fromString(created.get("id").asText());

        Subscriptions subs = subscribe(gameId);
        int firstLegal = rules.getLegalMoves(OthelloState.initial()).get(0).square();

        // The POST returns the state after only the HUMAN's move — it did not wait for the Hard search.
        JsonNode response = post("/api/games/" + gameId + "/moves", token, "{\"position\":" + firstLegal + "}");
        assertThat(response.get("moveCount").asInt()).isEqualTo(1);
        assertThat(response.get("currentTurn").asText()).isEqualTo("WHITE"); // the bot's turn now

        // The bot's reply is pushed over WebSocket shortly after, advancing the game to move 2.
        Map<String, Object> event = subs.topic().poll(10, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("type")).isEqualTo("MOVE_MADE");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) event.get("state");
        assertThat(((Number) state.get("moveCount")).intValue()).isEqualTo(2);
        assertThat(state.get("currentTurn")).isEqualTo("BLACK"); // back to the human
        assertThat(state.get("status")).isEqualTo("IN_PROGRESS");

        // The personal queue gets a YOUR_TURN nudge now that the turn is back to the human.
        Map<String, Object> nudge = subs.personal().poll(5, TimeUnit.SECONDS);
        assertThat(nudge).isNotNull();
        assertThat(nudge.get("type")).isEqualTo("YOUR_TURN");
    }

    @Test
    void humanTerminalMovePushesGameOverAndSchedulesNoReply() throws Exception {
        // Crafted near-terminal position: Black=a1, White=h8 — Black (the human) has no legal move, and
        // one pass already stands, so the human's forced pass is the second consecutive pass → terminal.
        Game crafted = new Game();
        crafted.setOpponentType(OpponentType.HUMAN_VS_AI);
        crafted.setBotSide(BotSide.WHITE);
        crafted.setBotDifficulty(BotDifficulty.HARD);
        crafted.setBotRating(BotDifficulty.HARD.rating());
        crafted.setBlackPlayerId(humanId);
        crafted.setBoardBlack(OthelloState.bit(0));
        crafted.setBoardWhite(OthelloState.bit(63));
        crafted.setCurrentTurn(Player.BLACK);
        crafted.setConsecutivePasses(1);
        crafted = gamesRepo.save(crafted);
        UUID gameId = crafted.getId();

        // Sanity: the human really has no placement, so a pass is their only (forced) move.
        assertThat(rules.getLegalMoves(mapper.toState(crafted))).isEmpty();

        Subscriptions subs = subscribe(gameId);

        JsonNode response = post("/api/games/" + gameId + "/moves", token, "{\"pass\":true}");
        assertThat(response.get("status").asText()).isNotEqualTo("IN_PROGRESS"); // already terminal

        // GAME_OVER is pushed; no MOVE_MADE bot reply is ever scheduled.
        Map<String, Object> first = subs.topic().poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.get("type")).isEqualTo("GAME_OVER");
        // Drain briefly to prove the bot never replied (no MOVE_MADE / YOUR_TURN follow).
        assertThat(subs.topic().poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(subs.personal().poll(1, TimeUnit.SECONDS)).isNull();
    }

    /** The two destinations a participant listens on: the per-game topic and their personal queue. */
    private record Subscriptions(
            BlockingQueue<Map<String, Object>> topic, BlockingQueue<Map<String, Object>> personal) {}

    private Subscriptions subscribe(UUID gameId) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        StompSession session = stompClient
                .connectAsync(url(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        BlockingQueue<Map<String, Object>> topic = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> personal = new LinkedBlockingQueue<>();
        session.subscribe("/topic/games/" + gameId, mapHandler(topic));
        session.subscribe("/user/queue/notifications", mapHandler(personal));
        // Let the SUBSCRIBEs register before the move that triggers the pushes.
        Thread.sleep(300);
        return new Subscriptions(topic, personal);
    }

    private static StompFrameHandler mapHandler(BlockingQueue<Map<String, Object>> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((Map<String, Object>) payload);
            }
        };
    }

    private JsonNode post(String path, String bearer, String body) {
        RestClient.RequestBodySpec spec = RestClient.create()
                .post()
                .uri("http://localhost:" + port + path)
                .header(HttpHeaders.CONTENT_TYPE, "application/json");
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        try {
            return objectMapper.readTree(spec.body(body).retrieve().body(String.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String url() {
        return "ws://localhost:" + port + "/ws";
    }
}
