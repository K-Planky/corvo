package dev.kplanky.othello.config;

import dev.kplanky.othello.auth.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>The public surface is {@code /health} and {@code /api/auth/**} (register + login); every other
 * request must carry a valid JWT, verified by the {@link JwtAuthenticationFilter} which runs before
 * the username/password filter and populates the security context.
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
                        .anyRequest()
                        .authenticated())
                // Return 401 (not a redirect to a login page) on missing/invalid credentials.
                .exceptionHandling(eh ->
                        eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
