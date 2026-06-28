package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kplanky.othello.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Regression guard for the move anti-cheat status codes over a <em>real</em> HTTP server (JDK
 * {@link HttpClient} against the live port), not MockMvc. The {@link GameMoveAuthorizationTest}
 * MockMvc suite asserts the same 403/409/422 verdicts, but MockMvc does not perform the servlet
 * container's internal ERROR dispatch — so it cannot catch the failure this test exists for: a
 * {@code @ResponseStatus} exception forwards to {@code /error}, and under {@code STATELESS} sessions
 * + a once-per-request JWT filter (which skips error dispatches) that re-dispatch was unauthenticated
 * and got overwritten with 401, masking the real status. The fix permits the ERROR dispatch in the
 * security chain; without it this test sees 401 where it expects 403/422.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MoveAuthorizationHttpStatusTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void antiCheatStatusCodesSurviveTheRealErrorDispatch() throws Exception {
        String ownerToken = register("http-owner");
        String outsiderToken = register("http-outsider");

        // Owner plays Black (bot White), so it's the owner's turn at the opening.
        HttpResponse<String> created =
                post("/api/games", "{\"difficulty\":\"EASY\",\"botSide\":\"WHITE\"}", ownerToken);
        assertThat(created.statusCode()).isEqualTo(201);
        String gameId = objectMapper.readTree(created.body()).get("id").asText();
        String movePath = "/api/games/" + gameId + "/moves";

        // 403: a non-participant — the bug surfaced here as 401.
        assertThat(post(movePath, "{\"position\":19}", outsiderToken).statusCode()).isEqualTo(403);

        // 422: the owner plays a1 (square 0), which is empty but brackets nothing.
        assertThat(post(movePath, "{\"position\":0}", ownerToken).statusCode()).isEqualTo(422);

        // 401 must still mean unauthenticated — a missing token is genuinely rejected.
        assertThat(post(movePath, "{\"position\":19}", null).statusCode()).isEqualTo(401);
    }

    private String register(String username) throws Exception {
        HttpResponse<String> resp = post(
                "/api/auth/register",
                "{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.com\",\"password\":\"correcthorse\"}",
                null);
        return objectMapper.readTree(resp.body()).get("token").asText();
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
