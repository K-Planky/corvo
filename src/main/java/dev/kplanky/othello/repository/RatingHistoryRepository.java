package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.RatingHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RatingHistory} (spec §5). Stats queries land in M7. */
public interface RatingHistoryRepository extends JpaRepository<RatingHistory, UUID> {}
