package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.RatingHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RatingHistory} (spec §5). */
public interface RatingHistoryRepository extends JpaRepository<RatingHistory, UUID> {

    /** A user's rating changes oldest-first, the chronological series for the stats/graph endpoint (§9). */
    List<RatingHistory> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
