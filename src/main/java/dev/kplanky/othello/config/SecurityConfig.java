package dev.kplanky.othello.config;

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

/**
 * Stateless security wiring (spec §10). Sessions are disabled and CSRF is off because auth is
 * token-based, not cookie-based. BCrypt hashes passwords.
 *
 * <p>M3.1 establishes the chain and opens the public surface ({@code /health}, {@code /api/auth/**})
 * so registration is reachable; everything else requires authentication. The JWT verification
 * filter that actually authenticates protected requests is added in M3.2.
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
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/health", "/api/auth/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Return 401 (not a redirect to a login page) on missing/invalid credentials.
                .exceptionHandling(eh ->
                        eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
