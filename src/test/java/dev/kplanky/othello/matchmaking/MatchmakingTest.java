package dev.kplanky.othello.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.OpponentType;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * M9.1 acceptance (spec §9/§15): two queued players are paired into a {@code HUMAN_VS_HUMAN} game and
 * both receive {@code MATCH_FOUND} (with the new {@code gameId}) on their personal queue. Also covers
 * the queue mechanics, leaving dequeues, and a double-join is idempotent (no self-pairing).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MatchmakingTest {

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
    MatchmakingService matchmaking;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        // Clear in FK order so the suite is order-independent (children before parents).
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();
        // The queue is a process-lifetime singleton, reset it too, or a leftover (now-stale) user id
        // would pair with the next test's player and hit the games→users FK.
        matchmaking.clear();

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void pairsTwoPlayersIntoAPvpGameAndPushesMatchFoundToBoth() throws Exception {
        Player alice = register("alice");
        Player bob = register("bob");

        BlockingQueue<Map<String, Object>> aliceQueue = subscribePersonal(alice.token());
        BlockingQueue<Map<String, Object>> bobQueue = subscribePersonal(bob.token());

        // Alice joins first and waits; Bob's join pairs them.
        MatchmakingStatus aliceJoin = joinQueue(alice.token());
        assertThat(aliceJoin.status()).isEqualTo("QUEUED");
        assertThat(aliceJoin.gameId()).isNull();

        MatchmakingStatus bobJoin = joinQueue(bob.token());
        assertThat(bobJoin.status()).isEqualTo("MATCHED");
        assertThat(bobJoin.gameId()).isNotNull();
        UUID gameId = bobJoin.gameId();

        // A HUMAN_VS_HUMAN game exists with both users as participants, one Black one White, no bot.
        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getOpponentType()).isEqualTo(OpponentType.HUMAN_VS_HUMAN);
        assertThat(game.getBotSide()).isEqualTo(BotSide.NONE);
        assertThat(game.getBotDifficulty()).isNull();
        assertThat(game.getBotRating()).isNull();
        assertThat(List.of(game.getBlackPlayerId(), game.getWhitePlayerId()))
                .containsExactlyInAnyOrder(alice.id(), bob.id());

        // Both players receive MATCH_FOUND carrying the new gameId on their personal queue.
        assertMatchFound(aliceQueue, gameId);
        assertMatchFound(bobQueue, gameId);
    }

    @Test
    void leavingTheQueuePreventsPairing() throws Exception {
        Player alice = register("alice");
        Player carol = register("carol");

        assertThat(joinQueue(alice.token()).status()).isEqualTo("QUEUED");
        leaveQueue(alice.token());

        // Carol joins an empty queue (Alice left) → she waits, no game is created.
        assertThat(joinQueue(carol.token()).status()).isEqualTo("QUEUED");
        assertThat(games.count()).isZero();
    }

    @Test
    void doubleJoinIsIdempotentAndNeverSelfPairs() throws Exception {
        Player alice = register("alice");

        assertThat(joinQueue(alice.token()).status()).isEqualTo("QUEUED");
        // A second join by the same user must not pair her with herself.
        assertThat(joinQueue(alice.token()).status()).isEqualTo("QUEUED");
        assertThat(games.count()).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Player(UUID id, String token) {}

    private record MatchmakingStatus(String status, UUID gameId) {}

    private void assertMatchFound(BlockingQueue<Map<String, Object>> queue, UUID gameId)
            throws Exception {
        Map<String, Object> event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("type")).isEqualTo("MATCH_FOUND");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) event.get("state");
        assertThat(state.get("id")).isEqualTo(gameId.toString());
        assertThat(state.get("opponentType")).isEqualTo("HUMAN_VS_HUMAN");
    }

    private Player register(String username) throws Exception {
        JsonNode auth = objectMapper.readTree(RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/auth/register")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"correcthorse\"}")
                .retrieve()
                .body(String.class));
        return new Player(
                UUID.fromString(auth.get("user").get("id").asText()), auth.get("token").asText());
    }

    private MatchmakingStatus joinQueue(String token) throws Exception {
        JsonNode body = objectMapper.readTree(RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/matchmaking/queue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class));
        JsonNode gameId = body.get("gameId");
        return new MatchmakingStatus(
                body.get("status").asText(),
                gameId == null || gameId.isNull() ? null : UUID.fromString(gameId.asText()));
    }

    private void leaveQueue(String token) {
        RestClient.create()
                .delete()
                .uri("http://localhost:" + port + "/api/matchmaking/queue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    private BlockingQueue<Map<String, Object>> subscribePersonal(String token) throws Exception {
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
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
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
        // Let the SUBSCRIBE register before a join can trigger the push.
        Thread.sleep(300);
        return sink;
    }
}
