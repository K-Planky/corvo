package dev.kplanky.othello.config;

import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the (Spring-agnostic) rules engine into the application context so services depend on the
 * generic {@link GameRules} seam rather than a concrete class (spec §6). The difficulty-aware AI
 * search lives behind {@code BotEngine}, which builds a per-tier search per move from the
 * {@code bot.*} difficulty tuning ({@link BotProperties}).
 */
@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class EngineConfig {

    @Bean
    public GameRules<OthelloState, OthelloMove> othelloRules() {
        return new OthelloRules();
    }
}
