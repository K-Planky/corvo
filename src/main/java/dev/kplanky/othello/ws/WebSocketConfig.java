package dev.kplanky.othello.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP-over-WebSocket wiring (spec §9). The single {@code /ws} endpoint accepts a native WebSocket
 * upgrade (Caddy passes it through transparently in prod, §13); STOMP itself carries the JWT, so the
 * HTTP handshake is anonymous and {@link StompAuthChannelInterceptor} authenticates the {@code
 * CONNECT}. An in-memory simple broker serves the push-only design: {@code /topic/games/{id}} for
 * per-game events and {@code /user/queue/...} for personal nudges; clients send no STOMP commands
 * (moves go over REST, §4), so the {@code /app} prefix exists only for completeness.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * Spring Boot 4.1 gotcha (spec Appendix B): with Jackson on the classpath the STOMP message
     * converter auto-config requires a {@link JsonMapper} bean, or the context fails to start.
     */
    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Same-origin in prod (SPA + API behind one reverse proxy, §13); allow any origin in dev so
        // the Vite dev server can connect. STOMP CONNECT auth, not handshake origin, is the gate.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
