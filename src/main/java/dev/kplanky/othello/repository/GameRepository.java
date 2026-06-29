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
}
