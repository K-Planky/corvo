package dev.kplanky.othello.auth;

import dev.kplanky.othello.auth.dto.AuthResponse;
import dev.kplanky.othello.auth.dto.LoginRequest;
import dev.kplanky.othello.auth.dto.RegisterRequest;
import dev.kplanky.othello.auth.dto.UserResponse;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.repository.UserRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration use case (spec §5/§10): hash the password with BCrypt, persist the user, and issue a
 * JWT. Duplicate username/email is rejected with a {@link DuplicateRegistrationException} (409).
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * A throwaway BCrypt hash compared against when the username is unknown, so a missing user and a
     * wrong password both pay the (~100ms) hashing cost — closes the timing side-channel that would
     * otherwise let an attacker enumerate valid usernames.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("invalid-credentials-timing-placeholder");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Pre-check for a friendly 409. The DB unique constraints (V2) are the authoritative guard
        // against the check-then-insert race; the catch below maps that to the same 409.
        if (users.existsByUsername(request.username())) {
            throw new DuplicateRegistrationException("username already taken");
        }
        if (users.existsByEmail(request.email())) {
            throw new DuplicateRegistrationException("email already registered");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.username(), request.email(), passwordHash);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent registration; the unique index rejected the insert.
            throw new DuplicateRegistrationException("username or email already registered");
        }

        return new AuthResponse(jwtService.issueToken(user), UserResponse.from(user));
    }

    /**
     * Verify credentials and issue a token. An unknown username and a wrong password both raise the
     * same {@link InvalidCredentialsException} (401) so the response can't be used to enumerate
     * accounts.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = users.findByUsername(request.username()).orElse(null);
        // Always run a BCrypt comparison (against a dummy hash when the user is absent) so the
        // response time can't be used to tell a real username from a fake one.
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return new AuthResponse(jwtService.issueToken(user), UserResponse.from(user));
    }

    /**
     * The current user behind a valid JWT (spec §9, {@code GET /api/auth/me}). Lets a returning
     * client rehydrate its session from a stored token without re-entering credentials, and re-read
     * its now-current Elo after a game. A token whose user no longer exists is treated as invalid
     * (401) so the client clears it and falls back to the sign-in screen.
     */
    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return users.findById(userId).map(UserResponse::from).orElseThrow(InvalidCredentialsException::new);
    }
}
