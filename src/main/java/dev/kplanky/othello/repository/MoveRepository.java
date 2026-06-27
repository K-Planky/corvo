package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.Move;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link Move} (spec §5). */
public interface MoveRepository extends JpaRepository<Move, UUID> {

    /**
     * The ordered move history of a game (move 1, 2, 3, …). Backed by the
     * {@code UNIQUE (game_id, move_number)} index, whose leading {@code game_id} column also serves
     * this {@code WHERE game_id = ? ORDER BY move_number} access pattern. Used for replay/resume (§5).
     */
    List<Move> findByGameIdOrderByMoveNumberAsc(UUID gameId);
}
