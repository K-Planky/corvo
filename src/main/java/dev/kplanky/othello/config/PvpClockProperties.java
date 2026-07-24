package dev.kplanky.othello.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PvP turn-clock configuration (spec §15, M10), bound from the {@code pvp.clock.*} properties. Each
 * player in a HUMAN_VS_HUMAN game gets a total time bank of {@code initial}; a scheduled server-side
 * sweep forfeits whoever lets their bank run out on their turn.
 *
 * <p>This binds only {@code initial}, the one value read in service logic (seeded onto a new PvP
 * game). The sweep's cadence ({@code pvp.clock.check-interval-ms}) and its on/off switch ({@code
 * pvp.clock.scheduler-enabled}, which the deterministic tests disable) are pure infrastructure knobs
 * read directly by the scheduler's annotations, so they intentionally live outside this record.
 */
@ConfigurationProperties(prefix = "pvp.clock")
public record PvpClockProperties(Duration initial) {

    public PvpClockProperties {
        if (initial == null) {
            initial = Duration.ofMinutes(5);
        }
    }

    /** The per-player starting bank in milliseconds (what a fresh PvP game seeds both clocks to). */
    public long initialMs() {
        return initial.toMillis();
    }
}
