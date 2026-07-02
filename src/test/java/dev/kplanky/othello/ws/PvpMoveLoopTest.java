package dev.kplanky.othello.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.rating.Elo;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.lang.reflect.Type;
import java.util.List;
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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * M9.2 acceptance (spec §8/§9/§15): a full human-vs-human move loop reuses the M4 orchestration +
 * authorization (403/409/422) and the M8 WebSocket push (the opponent's move arrives as
 * {@code MOVE_MADE}), and both Elo ratings update <em>symmetrically</em> at game end (each scored
 * against the other's pre-game rating).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PvpMoveLoopTest {

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
    void opponentMovesArriveOverWebSocketBothDirections() throws Exception {
        Account alice = register("alice"); // Black — moves first
        Account bob = register("bob"); // White
        UUID gameId = newPvpGame(alice.id(), bob.id(), OthelloState.initial());

        BlockingQueue<Map<String, Object>> aliceTopic = subscribeTopic(alice.token(), gameId);
        BlockingQueue<Map<String, Object>> bobTopic = subscribeTopic(bob.token(), gameId);

        // Black plays a legal opening move; the response reflects only her move (moveCount 1, White next).
        int blackMove = rules.getLegalMoves(OthelloState.initial()).get(0).square();
        JsonNode blackResp = postMove(alice.token(), gameId, "{\"position\":" + blackMove + "}");
        assertThat(blackResp.get("moveCount").asInt()).isEqualTo(1);
        assertThat(blackResp.get("currentTurn").asText()).isEqualTo("WHITE");

        // Bob (the opponent, who did not POST) receives it as a MOVE_MADE, oriented to him — it is now
        // his turn, so the pushed state carries White's legal moves. (The topic is a broadcast, so the
        // mover receives her own move too; each side just filters to the move it cares about.)
        Map<String, Object> toBob = pollUntilMoveCount(bobTopic, 1);
        assertThat(toBob.get("type")).isEqualTo("MOVE_MADE");
        Map<String, Object> bobState = state(toBob);
        assertThat(bobState.get("currentTurn")).isEqualTo("WHITE");
        @SuppressWarnings("unchecked")
        List<Integer> whiteLegal = (List<Integer>) bobState.get("legalMoves");
        assertThat(whiteLegal).isNotEmpty();

        // White replies; Black now receives the opponent's move over her topic (moveCount 2, Black next).
        postMove(bob.token(), gameId, "{\"position\":" + whiteLegal.get(0) + "}");
        Map<String, Object> toAlice = pollUntilMoveCount(aliceTopic, 2);
        assertThat(toAlice.get("type")).isEqualTo("MOVE_MADE");
        assertThat(state(toAlice).get("currentTurn")).isEqualTo("BLACK");
    }

    @Test
    void moveAuthorizationMirrorsTheRestAntiCheat() throws Exception {
        Account alice = register("alice"); // Black, to move
        Account bob = register("bob"); // White
        Account carol = register("carol"); // not a participant
        UUID gameId = newPvpGame(alice.id(), bob.id(), OthelloState.initial());

        int blackMove = rules.getLegalMoves(OthelloState.initial()).get(0).square();
        // 403: a non-participant may not move in this game.
        assertThat(moveStatus(carol.token(), gameId, "{\"position\":" + blackMove + "}")).isEqualTo(403);
        // 409: it is Black's turn, so White submitting is out of turn.
        assertThat(moveStatus(bob.token(), gameId, "{\"position\":" + blackMove + "}")).isEqualTo(409);
        // 422: the mover is on turn but a1 (square 0) is not a legal opening placement.
        assertThat(moveStatus(alice.token(), gameId, "{\"position\":0}")).isEqualTo(422);
    }

    @Test
    void terminalMoveUpdatesBothRatingsSymmetricallyAndPushesGameOver() throws Exception {
        Account alice = register("alice"); // Black
        Account bob = register("bob"); // White

        // A crafted position where neither side has a legal placement: Black owns a1/a2/a3, White owns
        // h8, and White has already passed (consecutivePasses = 1). Black's forced pass is the second
        // consecutive pass ⇒ terminal, and Black (3 discs) beats White (1) — a decisive result.
        long black = OthelloState.bit(0) | OthelloState.bit(8) | OthelloState.bit(16);
        long white = OthelloState.bit(63);
        OthelloState nearTerminal = new OthelloState(black, white, Player.BLACK, 1);
        assertThat(rules.getLegalMoves(nearTerminal)).isEmpty(); // Black must pass
        UUID gameId = newPvpGame(alice.id(), bob.id(), nearTerminal);

        BlockingQueue<Map<String, Object>> bobTopic = subscribeTopic(bob.token(), gameId);

        int aliceBefore = users.findById(alice.id()).orElseThrow().getEloRating();
        int bobBefore = users.findById(bob.id()).orElseThrow().getEloRating();

        JsonNode resp = postMove(alice.token(), gameId, "{\"pass\":true}");
        assertThat(resp.get("status").asText()).isEqualTo("BLACK_WON");

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getStatus()).isEqualTo(GameStatus.BLACK_WON);
        assertThat(game.getWinnerId()).isEqualTo(alice.id()); // the winning human

        // Both ratings moved, each scored against the other's PRE-game rating (order-independent): the
        // winner gains exactly what the loser drops for equal starting ratings.
        User aliceAfter = users.findById(alice.id()).orElseThrow();
        User bobAfter = users.findById(bob.id()).orElseThrow();
        assertThat(aliceAfter.getEloRating()).isEqualTo(Elo.updatedRating(aliceBefore, bobBefore, Elo.WIN));
        assertThat(bobAfter.getEloRating()).isEqualTo(Elo.updatedRating(bobBefore, aliceBefore, Elo.LOSS));
        assertThat(aliceAfter.getEloRating() - aliceBefore).isEqualTo(bobBefore - bobAfter.getEloRating());
        // One RatingHistory row per human.
        assertThat(ratings.findByUserIdOrderByCreatedAtAsc(alice.id())).hasSize(1);
        assertThat(ratings.findByUserIdOrderByCreatedAtAsc(bob.id())).hasSize(1);

        // The opponent is pushed GAME_OVER carrying the final result (it follows the terminal MOVE_MADE).
        Map<String, Object> over = pollUntilType(bobTopic, "GAME_OVER");
        assertThat(state(over).get("status")).isEqualTo("BLACK_WON");
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

    /** POSTs a move and returns the HTTP status code (for the anti-cheat rejection cases). */
    private int moveStatus(String token, UUID gameId, String body) {
        try {
            RestClient.create()
                    .post()
                    .uri("http://localhost:" + port + "/api/games/" + gameId + "/moves")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
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
        Thread.sleep(300); // let the SUBSCRIBE register before a move triggers the push
        return sink;
    }

    /** Drains pushes until one whose state has the given {@code moveCount} (the topic is a broadcast). */
    private static Map<String, Object> pollUntilMoveCount(
            BlockingQueue<Map<String, Object>> queue, int moveCount) throws Exception {
        return pollUntil(queue, e -> ((Number) state(e).get("moveCount")).intValue() == moveCount);
    }

    /** Drains pushes until one of the given event {@code type} (e.g. GAME_OVER follows a MOVE_MADE). */
    private static Map<String, Object> pollUntilType(
            BlockingQueue<Map<String, Object>> queue, String type) throws Exception {
        return pollUntil(queue, e -> type.equals(e.get("type")));
    }

    private static Map<String, Object> pollUntil(
            BlockingQueue<Map<String, Object>> queue,
            java.util.function.Predicate<Map<String, Object>> match)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && match.test(event)) {
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
