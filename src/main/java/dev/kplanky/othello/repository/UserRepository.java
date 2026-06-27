package dev.kplanky.othello.repository;

import dev.kplanky.othello.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link User} (spec §5). Auth finders land in M3. */
public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Optional<User> findByUsername(String username);
}
