package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.rating.Elo;
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
 * M11.2 acceptance (spec §15): the opponent-disconnect policy. A real STOMP disconnect fires {@code
 * OPPONENT_DISCONNECTED} and arms a grace timer; a reconnect within grace fires {@code
 * OPPONENT_RECONNECTED} and resumes the game untouched; a lapse forfeits the absent player — a rated
 * win for the present player (the documented policy) — and pushes {@code GAME_OVER}. The background
 * sweep is disabled suite-wide (pom Surefire {@code pvp.disconnect.scheduler-enabled=false}); the lapse
 * is driven deterministically via {@link DisconnectPolicyService#sweep(Instant)} with a {@code now}
 * past the armed deadline, so no test sleeps on the real grace window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpponentDisconnectPolicyTest {

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
    DisconnectPolicyService disconnectPolicy;

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
    void reconnectWithinGraceResumesTheGame() throws Exception {
        Account alice = register("alice"); // Black
        Account bob = register("bob"); // White — the player who stays
        UUID gameId = newPvpGame(alice.id(), bob.id());

        // Bob stays connected and subscribed, so he sees the presence events on the game topic.
        StompSession bobSession = connect(bob.token());
        BlockingQueue<Map<String, Object>> bobTopic = subscribe(bobSession, gameId);

        // Alice connects then drops: her disconnect arms the grace timer and pushes OPPONENT_DISCONNECTED.
        StompSession aliceSession = connect(alice.token());
        Thread.sleep(200); // let her SessionConnected register before she drops
        aliceSession.disconnect();
        assertThat(pollUntilType(bobTopic, "OPPONENT_DISCONNECTED")).isNotNull();

        // Alice reconnects within grace: OPPONENT_RECONNECTED is pushed and the grace timer is cancelled.
        connect(alice.token());
        assertThat(pollUntilType(bobTopic, "OPPONENT_RECONNECTED")).isNotNull();

        // Even a sweep well past the (now-cancelled) deadline must not forfeit — the game resumes.
        disconnectPolicy.sweep(Instant.now().plusSeconds(3600));
        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    void graceLapseForfeitsTheAbsentPlayerAsARatedWin() throws Exception {
        Account alice = register("alice"); // Black — the one who disconnects
        Account bob = register("bob"); // White — the present player, wins on the lapse
        UUID gameId = newPvpGame(alice.id(), bob.id());

        StompSession bobSession = connect(bob.token());
        BlockingQueue<Map<String, Object>> bobTopic = subscribe(bobSession, gameId);

        int aliceBefore = users.findById(alice.id()).orElseThrow().getEloRating();
        int bobBefore = users.findById(bob.id()).orElseThrow().getEloRating();

        StompSession aliceSession = connect(alice.token());
        Thread.sleep(200);
        aliceSession.disconnect();
        assertThat(pollUntilType(bobTopic, "OPPONENT_DISCONNECTED")).isNotNull();

        // Grace lapses (a now far past the deadline) while Alice is still offline ⇒ she is forfeited.
        disconnectPolicy.sweep(Instant.now().plusSeconds(3600));

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getStatus()).isEqualTo(GameStatus.WHITE_WON); // Bob (White) awarded the win
        assertThat(game.getWinnerId()).isEqualTo(bob.id());

        // Rated, symmetric: Bob gains exactly what Alice drops (equal starting ratings), one RatingHistory each.
        int aliceAfter = users.findById(alice.id()).orElseThrow().getEloRating();
        int bobAfter = users.findById(bob.id()).orElseThrow().getEloRating();
        assertThat(bobAfter).isEqualTo(Elo.updatedRating(bobBefore, aliceBefore, Elo.WIN));
        assertThat(aliceAfter).isEqualTo(Elo.updatedRating(aliceBefore, bobBefore, Elo.LOSS));
        assertThat(bobAfter - bobBefore).isEqualTo(aliceBefore - aliceAfter);
        assertThat(ratings.findByUserIdOrderByCreatedAtAsc(alice.id())).hasSize(1);
        assertThat(ratings.findByUserIdOrderByCreatedAtAsc(bob.id())).hasSize(1);

        // The present player is pushed the terminal result over WS.
        Map<String, Object> over = pollUntilType(bobTopic, "GAME_OVER");
        assertThat(state(over).get("status")).isEqualTo("WHITE_WON");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Account(UUID id, String token) {}

    /** Persists a HUMAN_VS_HUMAN game (Black to move, initial board) with Alice=Black, Bob=White. */
    private UUID newPvpGame(UUID blackId, UUID whiteId) {
        OthelloState state = OthelloState.initial();
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
        Thread.sleep(300); // let the SUBSCRIBE register before an event triggers a push
        return sink;
    }

    /** Drains pushes until one of the given event {@code type} (the topic is a broadcast). */
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
