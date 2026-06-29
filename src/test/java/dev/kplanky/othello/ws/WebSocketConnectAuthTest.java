package dev.kplanky.othello.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * M8.1 acceptance (spec §9/§10): a STOMP {@code CONNECT} succeeds only with a valid JWT; a missing or
 * invalid token is rejected so no session opens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WebSocketConnectAuthTest {

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketStompClient stompClient;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        // Clear the full FK child chain in order so this is order-independent under the shared
        // Testcontainers Postgres (a game-creating WS test may run before this one) — see DECISIONS.
        ratings.deleteAll();
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());

        String body = RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/auth/register")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"username\":\"wsuser\",\"email\":\"wsuser@example.com\",\"password\":\"correcthorse\"}")
                .retrieve()
                .body(String.class);
        JsonNode auth = objectMapper.readTree(body);
        this.token = auth.get("token").asText();
    }

    @Test
    void connectWithValidJwtSucceeds() throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        StompSession session = stompClient
                .connectAsync(url(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void connectWithoutJwtIsRejected() {
        assertThatThrownBy(() -> stompClient
                        .connectAsync(url(), new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void connectWithInvalidJwtIsRejected() {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token");

        assertThatThrownBy(() -> stompClient
                        .connectAsync(url(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    private String url() {
        return "ws://localhost:" + port + "/ws";
    }
}
