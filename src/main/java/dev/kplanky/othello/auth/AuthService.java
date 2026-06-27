package dev.kplanky.othello.auth;

import dev.kplanky.othello.auth.dto.AuthResponse;
import dev.kplanky.othello.auth.dto.RegisterRequest;
import dev.kplanky.othello.auth.dto.UserResponse;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.repository.UserRepository;
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

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
}
