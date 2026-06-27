package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link User} (spec §5). Finder methods for auth land in M3. */
public interface UserRepository extends JpaRepository<User, UUID> {}
