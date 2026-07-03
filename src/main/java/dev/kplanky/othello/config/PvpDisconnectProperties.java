package dev.kplanky.othello.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PvP opponent-disconnect policy configuration (spec §15, M11.2), bound from {@code pvp.disconnect.*}.
 * When a participant's WebSocket drops, a grace timer of {@code grace} starts; if they do not
 * reconnect before it lapses, a scheduled sweep forfeits them (a rated win for the present player —
 * the documented policy).
 *
 * <p>Binds only {@code grace} — the one value read in service logic. The sweep's cadence ({@code
 * pvp.disconnect.check-interval-ms}) and its on/off switch ({@code pvp.disconnect.scheduler-enabled},
 * which the deterministic tests disable) are pure infrastructure knobs read directly by the
 * scheduler's annotations, so they intentionally live outside this record — mirroring
 * {@link PvpClockProperties}.
 */
@ConfigurationProperties(prefix = "pvp.disconnect")
public record PvpDisconnectProperties(Duration grace) {

    public PvpDisconnectProperties {
        if (grace == null) {
            grace = Duration.ofSeconds(30);
        }
    }

    /** The disconnect grace window in milliseconds before a non-returning player is forfeited. */
    public long graceMs() {
        return grace.toMillis();
    }
}
