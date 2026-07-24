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
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.GameService;
import dev.kplanky.othello.game.TurnClockService;
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
 * M10.1 acceptance (spec §15): the PvP turn clock is server-authoritative. A player whose time bank
 * runs out on their turn is forfeited by the server-side sweep, a rated win for the opponent, and the
 * result is pushed over WebSocket as {@code GAME_OVER}. A game with time left is untouched, a move that
 * lands before the sweep cancels the forfeit (the server re-checks under the transaction), and vs-AI
 * games carry no clock and are never swept.
 *
 * <p>The background {@code @Scheduled} trigger is disabled suite-wide (Surefire sets {@code
 * pvp.clock.scheduler-enabled=false}); the tests drive {@link TurnClockService#sweep()} directly and
 * backdate {@code turnStartedAt} to make the clock deterministic without sleeping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TurnClockForfeitTest {

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

    @Autowired
    TurnClockService turnClock;

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
    void expiredTurnIsForfeitedServerSideWithSymmetricEloAndGameOverPush() throws Exception {
        Account alice = register("alice"); // Black, to move, and out of time
        Account bob = register("bob"); // White, wins on the flag

        // Black is to move but their clock is exhausted (1 s bank, started 5 s ago ⇒ 0 remaining).
        UUID gameId = newClockedPvpGame(alice.id(), bob.id(), 1_000L, 60_000L, secondsAgo(5));

        BlockingQueue<Map<String, Object>> bobTopic = subscribeTopic(bob.token(), gameId);

        int aliceBefore = users.findById(alice.id()).orElseThrow().getEloRating();
        int bobBefore = users.findById(bob.id()).orElseThrow().getEloRating();

        turnClock.sweep(); // the server-side check, not the client

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getStatus()).isEqualTo(GameStatus.WHITE_WON); // Black (the offender) forfeits
        assertThat(game.getWinnerId()).isEqualTo(bob.id());

        // A flag-fall is a rated loss: the winner gains exactly what the loser drops (symmetric Elo).
        User aliceAfter = users.findById(alice.id()).orElseThrow();
        User bobAfter = users.findById(bob.id()).orElseThrow();
        assertThat(bobAfter.getEloRating()).isEqualTo(Elo.updatedRating(bobBefore, aliceBefore, Elo.WIN));
        assertThat(aliceAfter.getEloRating()).isEqualTo(Elo.updatedRating(aliceBefore, bobBefore, Elo.LOSS));
        assertThat(bobAfter.getEloRating() - bobBefore).isEqualTo(aliceBefore - aliceAfter.getEloRating());
        assertThat(ratings.findByUserIdOrderByCreatedAtAsc(alice.id())).hasSize(1);
        assertThat(ratings.findByUserIdOrderByCreatedAtAsc(bob.id())).hasSize(1);

        // The result is pushed over WebSocket to the game topic.
        Map<String, Object> over = pollUntilType(bobTopic, "GAME_OVER");
        assertThat(state(over).get("status")).isEqualTo("WHITE_WON");
    }

    @Test
    void gameWithTimeLeftIsNotForfeited() throws Exception {
        Account alice = register("alice");
        Account bob = register("bob");
        // Full banks, clock just started, Black has plenty of time.
        UUID gameId = newClockedPvpGame(alice.id(), bob.id(), 60_000L, 60_000L, Instant.now());

        BlockingQueue<Map<String, Object>> bobTopic = subscribeTopic(bob.token(), gameId);

        turnClock.sweep();

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(bobTopic.poll(1, TimeUnit.SECONDS)).isNull(); // no push
    }

    @Test
    void aMoveThatLandsBeforeTheSweepCancelsTheForfeit() throws Exception {
        Account alice = register("alice"); // Black, to move
        Account bob = register("bob"); // White
        // Black's clock is expired, but Black gets a move in before the sweep runs.
        UUID gameId = newClockedPvpGame(alice.id(), bob.id(), 1_000L, 60_000L, secondsAgo(5));

        int blackMove = rules.getLegalMoves(OthelloState.initial()).get(0).square();
        postMove(alice.token(), gameId, "{\"position\":" + blackMove + "}"); // resets turnStartedAt to now

        turnClock.sweep(); // now it is White's turn with a fresh, full clock, no forfeit

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(game.getCurrentTurn().name()).isEqualTo("WHITE");
    }

    @Test
    void sweepForfeitsEveryExpiredGameInOneTick() {
        Account alice = register("alice");
        Account bob = register("bob");
        // Two independent PvP games, both with Black's clock expired, one tick must resolve both
        // (a failure on one must not abort the loop before the other is checked).
        UUID g1 = newClockedPvpGame(alice.id(), bob.id(), 1_000L, 60_000L, secondsAgo(5));
        UUID g2 = newClockedPvpGame(bob.id(), alice.id(), 1_000L, 60_000L, secondsAgo(5));

        turnClock.sweep();

        assertThat(games.findById(g1).orElseThrow().getStatus()).isEqualTo(GameStatus.WHITE_WON);
        assertThat(games.findById(g2).orElseThrow().getStatus()).isEqualTo(GameStatus.WHITE_WON);
    }

    @Test
    void vsAiGamesAreUnclockedAndNeverSwept() {
        Account alice = register("alice");
        // A vs-AI game (bot plays White, human Black to move) carries no clock.
        UUID vsAi = gameService.createVsAiGame(alice.id(), BotDifficulty.EASY, BotSide.WHITE).id();

        assertThat(games.findActiveClockedPvpGameIds()).doesNotContain(vsAi);
        Game before = games.findById(vsAi).orElseThrow();
        assertThat(before.getTurnStartedAt()).isNull();

        turnClock.sweep();

        assertThat(games.findById(vsAi).orElseThrow().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Account(UUID id, String token) {}

    private static Instant secondsAgo(long seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    /** Persists a HUMAN_VS_HUMAN game (Black to move, initial board) with explicit clock state. */
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

    private Account register(String username) {
        try {
            JsonNode auth = objectMapper.readTree(RestClient.create()
                    .post()
                    .uri("http://localhost:" + port + "/api/auth/register")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"username\":\"" + username + "\",\"password\":\"correcthorse\"}")
                    .retrieve()
                    .body(String.class));
            return new Account(
                    UUID.fromString(auth.get("user").get("id").asText()), auth.get("token").asText());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void postMove(String token, UUID gameId, String body) {
        RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/games/" + gameId + "/moves")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(body)
                .retrieve()
                .toBodilessEntity();
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
        Thread.sleep(300); // let the SUBSCRIBE register before the sweep triggers the push
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
