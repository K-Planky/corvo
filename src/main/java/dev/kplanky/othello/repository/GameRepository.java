package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.Game;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link Game} (spec §5). Listing/filtering queries land in M4. */
public interface GameRepository extends JpaRepository<Game, UUID> {}
