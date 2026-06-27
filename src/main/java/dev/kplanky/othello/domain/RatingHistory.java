package dev.kplanky.othello.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One Elo rating change for a user resulting from a terminal game (spec §5/§8). Cheap audit trail
 * that backs the stats/graph endpoint (§9). Records the before/after ratings and the delta.
 */
@Entity
@Table(name = "rating_history")
public class RatingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "old_rating", nullable = false)
    private int oldRating;

    @Column(name = "new_rating", nullable = false)
    private int newRating;

    @Column(nullable = false)
    private int delta;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA requires a no-arg constructor. */
    protected RatingHistory() {}

    public RatingHistory(UUID userId, UUID gameId, int oldRating, int newRating) {
        this.userId = userId;
        this.gameId = gameId;
        this.oldRating = oldRating;
        this.newRating = newRating;
        this.delta = newRating - oldRating;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public int getOldRating() {
        return oldRating;
    }

    public int getNewRating() {
        return newRating;
    }

    public int getDelta() {
        return delta;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
