package dev.kplanky.othello.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M3.1 acceptance (spec §5/§10): {@code POST /api/auth/register} creates a user with a BCrypt hash
 * (never plaintext) and returns user + JWT; a duplicate username is rejected with 409; the
 * stored hash is asserted to differ from the raw password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthRegistrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        // Clear the child tables that reference users before deleting users: a prior game-creating
        // test class can leave games/moves/ratings rows (these tests share one Testcontainers
        // Postgres and test order isn't guaranteed), which would otherwise block users.deleteAll().
        ratings.deleteAll();
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    @Test
    void registerCreatesUserWithBcryptHashAndReturnsToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"username":"alice","password":"hunter2pw"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.eloRating").value(1200))
                // The password hash must never leak into the response.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$..password").doesNotExist());

        User stored = users.findByUsername("alice").orElseThrow();
        // Stored value is a BCrypt hash, not the raw password.
        assertThat(stored.getPasswordHash()).isNotEqualTo("hunter2pw");
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches("hunter2pw", stored.getPasswordHash()))
                .isTrue();
    }

    @Test
    void duplicateUsernameIsRejectedWith409() throws Exception {
        register("bob", "password1");

        // Same username.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"username":"bob","password":"password1"}
                                """))
                .andExpect(status().isConflict())
                // The reason is surfaced in the body so the client shows it (not a generic
                // status-based default written for a different context).
                .andExpect(jsonPath("$.message").value("username already taken"));

        assertThat(users.count()).isEqualTo(1);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(username, password)))
                .andExpect(status().isCreated());
    }
}
