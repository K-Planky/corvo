package dev.kplanky.othello.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * M8.2 acceptance (spec §9/§10): {@code /topic/games/{id}} is subscribable only by a participant, a
 * participant's subscription receives the game's pushes; a non-participant's {@code SUBSCRIBE} is
 * rejected by the interceptor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WebSocketTopicAuthorizationTest {

    @LocalServerPort
    int port;

    @Autowired
    SimpMessagingTemplate broker;

    @Autowired
    GameRepository gamesRepo;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    UserRepository users;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketStompClient stompClient;
    private String participantToken;
    private String outsiderToken;
    private UUID gameId;

    @BeforeEach
    void setUp() throws Exception {
        moves.deleteAll();
        ratings.deleteAll();
        gamesRepo.deleteAll();
        users.deleteAll();

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());

        participantToken = register("participant");
        outsiderToken = register("outsider");

        // The participant creates a vs-AI game, so they play one side and are the only human in it.
        String created = RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/games")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + participantToken)
                .body("{\"difficulty\":\"EASY\",\"botSide\":\"WHITE\"}")
                .retrieve()
                .body(String.class);
        gameId = UUID.fromString(objectMapper.readTree(created).get("id").asText());
    }

    @Test
    void participantReceivesGameTopicPushes() throws Exception {
        StompSession session = connect(participantToken);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/games/" + gameId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((String) payload);
            }
        });

        // The SUBSCRIBE registers asynchronously, so re-push until the participant receives it (or we
        // give up), robust under CI load, unlike a single fixed sleep.
        String got = null;
        for (int i = 0; i < 50 && got == null; i++) {
            broker.convertAndSend("/topic/games/" + gameId, "ping");
            got = received.poll(100, TimeUnit.MILLISECONDS);
        }

        assertThat(got).contains("ping");
        session.disconnect();
    }

    @Test
    void nonParticipantSubscribeIsRejected() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        StompSession session = connect(outsiderToken, errorLatch);

        session.subscribe("/topic/games/" + gameId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {}
        });

        // The interceptor rejects the SUBSCRIBE → server sends an ERROR frame and drops the session.
        assertThat(errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private String register(String username) throws Exception {
        String body = RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/auth/register")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"correcthorse\"}")
                .retrieve()
                .body(String.class);
        JsonNode auth = objectMapper.readTree(body);
        return auth.get("token").asText();
    }

    private StompSession connect(String token) throws Exception {
        return connect(token, new CountDownLatch(1));
    }

    private StompSession connect(String token, CountDownLatch errorLatch) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return stompClient
                .connectAsync(url(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession session, StompCommand command,
                            StompHeaders headers, byte[] payload, Throwable exception) {
                        errorLatch.countDown();
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        errorLatch.countDown();
                    }
                })
                .get(5, TimeUnit.SECONDS);
    }

    private String url() {
        return "ws://localhost:" + port + "/ws";
    }
}
