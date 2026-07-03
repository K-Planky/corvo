package dev.kplanky.othello.game;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The periodic trigger for the PvP opponent-disconnect sweep (spec §15, M11.2): a thin timer that
 * calls {@link DisconnectPolicyService#sweep()} every {@code pvp.disconnect.check-interval-ms}. Kept
 * separate from the sweep logic so it can be switched off in tests via {@code
 * pvp.disconnect.scheduler-enabled=false} (Surefire sets this) while the always-present {@link
 * DisconnectPolicyService} is still driven directly — the same "logic-vs-trigger" split the turn-clock
 * sweep uses ({@link TurnClockScheduler}).
 */
@Component
@ConditionalOnProperty(
        name = "pvp.disconnect.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DisconnectScheduler {

    private final DisconnectPolicyService disconnectPolicyService;

    public DisconnectScheduler(DisconnectPolicyService disconnectPolicyService) {
        this.disconnectPolicyService = disconnectPolicyService;
    }

    @Scheduled(fixedDelayString = "${pvp.disconnect.check-interval-ms:1000}")
    public void tick() {
        disconnectPolicyService.sweep();
    }
}
