package dev.kplanky.othello.config;

import dev.kplanky.othello.auth.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless security wiring (spec §10). Sessions are disabled and CSRF is off because auth is
 * token-based, not cookie-based. BCrypt hashes passwords.
 *
 * <p>The public surface is {@code /health}, {@code /api/auth/**} (register + login), and the
 * read-only {@code GET /api/leaderboard} (§9, auth optional); every other request must carry a valid
 * JWT, verified by the {@link JwtAuthenticationFilter} which runs before the username/password filter
 * and populates the security context.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** BCrypt password hashing (spec §10). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Let the container's internal ERROR dispatch through. A @ResponseStatus
                        // exception (403/409/422 from the move anti-cheat) forwards to /error; because
                        // the session is STATELESS and the JWT filter is once-per-request (it skips
                        // error dispatches), that re-dispatch is unauthenticated and would otherwise be
                        // overwritten with 401 by the entry point — masking the real status.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/health", "/api/auth/**")
                        .permitAll()
                        // The leaderboard is public (spec §9, auth optional) — readable without a JWT.
                        .requestMatchers(HttpMethod.GET, "/api/leaderboard")
                        .permitAll()
                        // SPA shell + assets are served same-origin by this app (spec §13) and must
                        // load before authentication; the API stays protected by anyRequest() below.
                        // No client-side router, so only the root, index.html, Vite's /assets/** and
                        // a few root icons are served — not a catch-all.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**",
                                "/*.svg", "/*.png", "/*.ico", "/*.webmanifest")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Return 401 (not a redirect to a login page) on missing/invalid credentials.
                .exceptionHandling(eh ->
                        eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
