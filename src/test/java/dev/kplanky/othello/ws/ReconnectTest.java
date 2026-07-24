package dev.kplanky.othello.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * M11.1 acceptance (spec §15): a client that drops and reconnects renders correct current state purely
 * from {@code GET /api/games/{id}} plus a re-subscribe, no client resync. Because the board is
 * server-authoritative in Postgres and the STOMP layer is stateless (auth per CONNECT, subscribe authz
 * re-checked per frame), a returning client just re-fetches state and re-subscribes: moves it missed
 * while its socket was down are already reflected in the GET, and live pushes resume on the new
 * subscription.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReconnectTest {

    @LocalServerPort
    int port;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void reconnectRendersCurrentStateFromGetThenResumesPushes() throws Exception {
        Account alice = register("alice"); // Black, moves first
        Account bob = register("bob"); // White
        UUID gameId = newPvpGame(alice.id(), bob.id(), OthelloState.initial());

        // Alice subscribes, then her socket DROPS (disconnect). Any push while she is gone is lost.
        StompSession dropped = connect(alice.token());
        subscribe(dropped, gameId);
        dropped.disconnect();

        // Two moves apply over REST (WS-independent) while Alice is offline: her opening move, then
        // Bob's reply. These MOVE_MADE pushes go to the topic Alice is no longer subscribed to.
        int blackMove = firstLegal(alice.token(), gameId);
        postMove(alice.token(), gameId, "{\"position\":" + blackMove + "}");
        int whiteMove = firstLegal(bob.token(), gameId);
        postMove(bob.token(), gameId, "{\"position\":" + whiteMove + "}");

        // Reconnect step: a plain GET returns the authoritative current state, the two missed moves are
        // already reflected, oriented to Alice (it is her turn again, so her legal moves are present).
        JsonNode state = getState(alice.token(), gameId);
        assertThat(state.get("moveCount").asInt()).isEqualTo(2);
        assertThat(state.get("currentTurn").asText()).isEqualTo("BLACK");
        assertThat(state.get("legalMoves")).isNotEmpty();
        // The board matches replaying the two known moves from the initial position, no client resync.
        OthelloState expected = rules.applyMove(
                rules.applyMove(OthelloState.initial(), OthelloMove.at(blackMove)),
                OthelloMove.at(whiteMove));
        assertThat(state.get("boardBlack").asLong()).isEqualTo(expected.black());
        assertThat(state.get("boardWhite").asLong()).isEqualTo(expected.white());

        // Re-subscribe on a fresh session; live pushes resume, a subsequent opponent move arrives.
        StompSession resumed = connect(alice.token());
        BlockingQueue<Map<String, Object>> resumedTopic = subscribe(resumed, gameId);
        postMove(alice.token(), gameId, "{\"position\":" + firstLegal(alice.token(), gameId) + "}");
        postMove(bob.token(), gameId, "{\"position\":" + firstLegal(bob.token(), gameId) + "}");

        Map<String, Object> push = pollUntilMoveCount(resumedTopic, 4);
        assertThat(push.get("type")).isEqualTo("MOVE_MADE");
        assertThat(state(push).get("currentTurn")).isEqualTo("BLACK");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Account(UUID id, String token) {}

    /** Persists a HUMAN_VS_HUMAN game with a known Black/White seating and starting position. */
    private UUID newPvpGame(UUID blackId, UUID whiteId, OthelloState state) {
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_HUMAN);
        game.setBlackPlayerId(blackId);
        game.setWhitePlayerId(whiteId);
        game.setBoardBlack(state.black());
        game.setBoardWhite(state.white());
        game.setCurrentTurn(state.toMove());
        game.setConsecutivePasses(state.consecutivePasses());
        return games.save(game).getId();
    }

    private Account register(String username) throws Exception {
        JsonNode auth = objectMapper.readTree(RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/auth/register")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"correcthorse\"}")
                .retrieve()
                .body(String.class));
        return new Account(
                UUID.fromString(auth.get("user").get("id").asText()), auth.get("token").asText());
    }

    private JsonNode postMove(String token, UUID gameId, String body) throws Exception {
        return objectMapper.readTree(RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/games/" + gameId + "/moves")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    private JsonNode getState(String token, UUID gameId) throws Exception {
        return objectMapper.readTree(RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/api/games/" + gameId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class));
    }

    /** The first square the token's user may legally play right now (their turn), read via GET state. */
    private int firstLegal(String token, UUID gameId) throws Exception {
        JsonNode legal = getState(token, gameId).get("legalMoves");
        assertThat(legal).isNotEmpty();
        return legal.get(0).asInt();
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        headers,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, UUID gameId)
            throws Exception {
        BlockingQueue<Map<String, Object>> sink = new LinkedBlockingQueue<>();
        session.subscribe("/topic/games/" + gameId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) {
                return Map.class;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                sink.add((Map<String, Object>) payload);
            }
        });
        Thread.sleep(300); // let the SUBSCRIBE register before a move triggers the push
        return sink;
    }

    /** Drains pushes until one whose state has the given {@code moveCount} (the topic is a broadcast). */
    private static Map<String, Object> pollUntilMoveCount(
            BlockingQueue<Map<String, Object>> queue, int moveCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && ((Number) state(event).get("moveCount")).intValue() == moveCount) {
                return event;
            }
        }
        throw new AssertionError("timed out waiting for the expected WebSocket push");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Map<String, Object> event) {
        return (Map<String, Object>) event.get("state");
    }
}
