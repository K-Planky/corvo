package dev.kplanky.othello.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.GameService;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.lang.reflect.Type;
import java.time.Instant;
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
 * M10.2 acceptance (spec §15): each side's remaining time is surfaced in game state and in the
 * WebSocket push. {@code GET /api/games/{id}} and the {@code MOVE_MADE} payload carry
 * {@code blackTimeRemainingMs}/{@code whiteTimeRemainingMs} for a clocked PvP game (and {@code null}
 * for an unclocked vs-AI game), and a move decrements the mover's bank by the time their turn took —
 * proving the clock is maintained server-side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TurnClockStateTest {

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

    @Autowired
    GameService gameService;

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
    void stateAndPushCarryRemainingTimeAndAMoveDecrementsTheMoverServerSide() throws Exception {
        Account alice = register("alice"); // Black, to move
        Account bob = register("bob"); // White
        // Both banks 60 s; Black's clock started 2 s ago, so a move now should charge Black ~2 s.
        UUID gameId = newClockedPvpGame(alice.id(), bob.id(), 60_000L, 60_000L, secondsAgo(2));

        // GET reflects the live countdown for the side to move (Black), the frozen bank for the idle side.
        JsonNode get = getState(alice.token(), gameId);
        assertThat(get.get("blackTimeRemainingMs").isNull()).isFalse();
        assertThat(get.get("whiteTimeRemainingMs").asLong()).isEqualTo(60_000L); // White frozen, full
        assertThat(get.get("blackTimeRemainingMs").asLong()).isLessThan(60_000L); // Black ticking down

        // Bob subscribes; Black's move is pushed to him as MOVE_MADE carrying both remaining times.
        BlockingQueue<Map<String, Object>> bobTopic = subscribeTopic(bob.token(), gameId);

        int blackMove = rules.getLegalMoves(OthelloState.initial()).get(0).square();
        JsonNode moveResp = postMove(alice.token(), gameId, "{\"position\":" + blackMove + "}");

        // The mover's bank was charged the ~2 s their turn took (server-side); White's is untouched and
        // now ticking from ~0 as the side to move.
        long blackAfter = moveResp.get("blackTimeRemainingMs").asLong();
        long whiteAfter = moveResp.get("whiteTimeRemainingMs").asLong();
        // Black was charged the ~2 s their turn took: clearly dropped (< 59 s) but with generous room
        // below (a slow WS handshake before the move adds latency) so the bound isn't flaky.
        assertThat(blackAfter).isBetween(50_000L, 59_000L);
        assertThat(whiteAfter).isBetween(58_000L, 60_000L); // White to move, barely elapsed
        assertThat(blackAfter).isLessThan(whiteAfter);

        Map<String, Object> pushed = pollUntilType(bobTopic, "MOVE_MADE");
        Map<String, Object> state = state(pushed);
        assertThat(state.get("blackTimeRemainingMs")).isNotNull();
        assertThat(state.get("whiteTimeRemainingMs")).isNotNull();
        assertThat(((Number) state.get("blackTimeRemainingMs")).longValue()).isEqualTo(blackAfter);
    }

    @Test
    void vsAiStateHasNoRemainingTime() throws Exception {
        Account alice = register("alice");
        // vs-AI (bot plays White, human Black to move) — no clock.
        UUID vsAi = gameService.createVsAiGame(alice.id(), BotDifficulty.EASY, BotSide.WHITE).id();

        JsonNode get = getState(alice.token(), vsAi);
        assertThat(get.get("blackTimeRemainingMs").isNull()).isTrue();
        assertThat(get.get("whiteTimeRemainingMs").isNull()).isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Account(UUID id, String token) {}

    private static Instant secondsAgo(long seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    private UUID newClockedPvpGame(
            UUID blackId, UUID whiteId, long blackMs, long whiteMs, Instant turnStartedAt) {
        OthelloState state = OthelloState.initial();
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_HUMAN);
        game.setBlackPlayerId(blackId);
        game.setWhitePlayerId(whiteId);
        game.setBoardBlack(state.black());
        game.setBoardWhite(state.white());
        game.setCurrentTurn(state.toMove());
        game.setConsecutivePasses(state.consecutivePasses());
        game.setBlackTimeMs(blackMs);
        game.setWhiteTimeMs(whiteMs);
        game.setTurnStartedAt(turnStartedAt);
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

    private JsonNode getState(String token, UUID gameId) throws Exception {
        return objectMapper.readTree(RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/api/games/" + gameId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class));
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

    private BlockingQueue<Map<String, Object>> subscribeTopic(String token, UUID gameId)
            throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        StompSession session = stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        headers,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

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
        Thread.sleep(300);
        return sink;
    }

    private static Map<String, Object> pollUntilType(
            BlockingQueue<Map<String, Object>> queue, String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && type.equals(event.get("type"))) {
                return event;
            }
        }
        throw new AssertionError("timed out waiting for a " + type + " push");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Map<String, Object> event) {
        return (Map<String, Object>) event.get("state");
    }
}
