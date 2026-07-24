package dev.kplanky.othello.domain;

import dev.kplanky.othello.engine.Player;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One move in a game's ordered history (spec §5). A move is either a disc placement on
 * {@code position} (0..63, indexed {@code row*8+col}) or a pass ({@code isPass = true}, with
 * {@code position} null). {@code flippedMask} records the discs this move flipped, enabling replay
 * and undo.
 *
 * <p>{@code moveNumber} orders moves within a game and is unique per game (enforced by a DB unique
 * constraint on {@code (game_id, move_number)}, see {@code V2__core_schema.sql}).
 */
@Entity
@Table(name = "moves")
public class Move {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "move_number", nullable = false)
    private int moveNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Player player;

    /** Square 0..63, or {@code null} when {@link #isPass} is true. Stored as {@code SMALLINT}. */
    @Column
    private Short position;

    @Column(name = "is_pass", nullable = false)
    private boolean pass;

    @Column(name = "flipped_mask", nullable = false)
    private long flippedMask;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA requires a no-arg constructor. */
    protected Move() {}

    /** A disc placement on {@code position} (0..63) flipping {@code flippedMask}. */
    public static Move placement(UUID gameId, int moveNumber, Player player, int position, long flippedMask) {
        Move move = new Move();
        move.gameId = gameId;
        move.moveNumber = moveNumber;
        move.player = player;
        move.position = (short) position;
        move.pass = false;
        move.flippedMask = flippedMask;
        return move;
    }

    /** A pass (no disc placed, nothing flipped). */
    public static Move pass(UUID gameId, int moveNumber, Player player) {
        Move move = new Move();
        move.gameId = gameId;
        move.moveNumber = moveNumber;
        move.player = player;
        move.position = null;
        move.pass = true;
        move.flippedMask = 0L;
        return move;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public int getMoveNumber() {
        return moveNumber;
    }

    public Player getPlayer() {
        return player;
    }

    public Short getPosition() {
        return position;
    }

    public boolean isPass() {
        return pass;
    }

    public long getFlippedMask() {
        return flippedMask;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
