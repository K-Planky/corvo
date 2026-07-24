package dev.kplanky.othello.game;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The periodic trigger for the PvP turn-clock sweep (spec §15, M10): a thin timer that calls
 * {@link TurnClockService#sweep()} every {@code pvp.clock.check-interval-ms}. Kept separate from the
 * sweep logic so it can be switched off in tests via {@code pvp.clock.scheduler-enabled=false}
 * (Surefire sets this) while the always-present {@link TurnClockService} is still driven directly,
 * the same "logic-vs-trigger" split the async AI reply uses for its own toggle.
 */
@Component
@ConditionalOnProperty(name = "pvp.clock.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class TurnClockScheduler {

    private final TurnClockService turnClockService;

    public TurnClockScheduler(TurnClockService turnClockService) {
        this.turnClockService = turnClockService;
    }

    @Scheduled(fixedDelayString = "${pvp.clock.check-interval-ms:1000}")
    public void tick() {
        turnClockService.sweep();
    }
}
