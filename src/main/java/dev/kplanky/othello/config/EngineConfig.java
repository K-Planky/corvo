package dev.kplanky.othello.config;

import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.RandomBot;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloRules;
import dev.kplanky.othello.engine.othello.OthelloState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the (Spring-agnostic) rules engine and AI search into the application context so services
 * depend on the generic {@link GameRules}/{@link Search} seams rather than concrete classes
 * (spec §6). Milestone 6 replaces the single random {@code botSearch} with difficulty-aware rungs.
 */
@Configuration
public class EngineConfig {

    @Bean
    public GameRules<OthelloState, OthelloMove> othelloRules() {
        return new OthelloRules();
    }

    /** The bot's move chooser for the single-player slice — rung 1 (uniformly random) for now. */
    @Bean
    public Search<OthelloState, OthelloMove> botSearch(GameRules<OthelloState, OthelloMove> rules) {
        return new RandomBot<>(rules);
    }
}
