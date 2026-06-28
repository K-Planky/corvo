package dev.kplanky.othello.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kplanky.othello.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M5.5 (spec §13): the SPA shell is served same-origin by this app and must load without auth,
 * while the API stays protected. The static files are bundled into the image at build time and are
 * NOT on the test classpath, so a permitted-but-absent path resolves to 404 — crucially never 401,
 * which is what a blocked request returns. We assert "not 401" so the test pins the security rule,
 * not resource presence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaticSpaAccessTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void spaShellPathsArePermittedWithoutAuth() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/assets/index-abc123.js", "/crow.svg"}) {
            int statusCode = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(statusCode)
                    .as("GET %s must be permitted (not 401) — it's the SPA shell", path)
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }

    @Test
    void apiStaysProtectedWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/games")).andExpect(status().isUnauthorized());
    }
}
