package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link Game} (spec §5). */
public interface GameRepository extends JpaRepository<Game, UUID> {

    /**
     * The caller's games (those they play either side of), newest first, optionally filtered by
     * {@code status} — pass {@code null} for all statuses. Backs {@code GET /api/games?status=…} (§9).
     */
    @Query("select g from Game g where (g.blackPlayerId = :userId or g.whitePlayerId = :userId) "
            + "and (:status is null or g.status = :status) order by g.updatedAt desc")
    List<Game> findForUser(@Param("userId") UUID userId, @Param("status") GameStatus status);

    /**
     * Whether {@code userId} plays either side of game {@code gameId}. Backs WebSocket topic
     * authorization (§9/§10) — a non-participant may not subscribe to a game's push topic — without
     * loading the whole {@link Game}. A non-existent game yields {@code false}.
     */
    @Query("select count(g) > 0 from Game g where g.id = :gameId "
            + "and (g.blackPlayerId = :userId or g.whitePlayerId = :userId)")
    boolean isParticipant(@Param("gameId") UUID gameId, @Param("userId") UUID userId);

    /**
     * Ids of in-progress, clocked PvP games — the candidates the turn-clock sweep checks for a timeout
     * (spec §15, M10). Id-only so the sweep re-reads each in its own transaction; {@code turnStartedAt
     * is not null} excludes vs-AI (unclocked) games. At this project's single-instance scale scanning
     * all active PvP games each tick is cheap; a persisted indexed deadline would be the scale upgrade.
     */
    @Query("select g.id from Game g where g.status = dev.kplanky.othello.domain.GameStatus.IN_PROGRESS "
            + "and g.opponentType = dev.kplanky.othello.domain.OpponentType.HUMAN_VS_HUMAN "
            + "and g.turnStartedAt is not null")
    List<UUID> findActiveClockedPvpGameIds();
}
