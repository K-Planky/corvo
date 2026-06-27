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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A single game (spec §5). The current position is stored denormalized as two bitboards
 * ({@code boardBlack}/{@code boardWhite}, see §5 board representation) for O(1) load, while the full
 * {@link Move} list enables replay/resume — the redundancy is intentional.
 *
 * <p>Player references are stored as nullable {@code UUID} columns ({@code null} when that side is a
 * bot — bots have no {@code User} row). {@code winnerId} is set only to a winning human; it stays
 * {@code null} when a bot wins, so {@code status} is the authoritative outcome (Appendix C, A1).
 *
 * <p>{@code version} is the optimistic lock guarding concurrent move application (§11).
 */
@Entity
@Table(name = "games")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "black_player_id")
    private UUID blackPlayerId;

    @Column(name = "white_player_id")
    private UUID whitePlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "opponent_type", nullable = false, length = 32)
    private OpponentType opponentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "bot_side", nullable = false, length = 8)
    private BotSide botSide = BotSide.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "bot_difficulty", length = 16)
    private BotDifficulty botDifficulty;

    @Column(name = "board_black", nullable = false)
    private long boardBlack;

    @Column(name = "board_white", nullable = false)
    private long boardWhite;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_turn", nullable = false, length = 8)
    private Player currentTurn;

    /**
     * Consecutive passes leading up to {@link #currentTurn}, completing the O(1) state snapshot so
     * double-pass termination (§6/§14) doesn't require replaying the move list. Reset to 0 by any
     * placement; reaches 2 only in a terminal (ended) game.
     */
    @Column(name = "consecutive_passes", nullable = false)
    private int consecutivePasses = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GameStatus status = GameStatus.IN_PROGRESS;

    @Column(name = "winner_id")
    private UUID winnerId;

    @Column(name = "move_count", nullable = false)
    private int moveCount = 0;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Public no-arg constructor: JPA requires one, and services build a game via the setters. */
    public Game() {}

    public UUID getId() {
        return id;
    }

    public UUID getBlackPlayerId() {
        return blackPlayerId;
    }

    public void setBlackPlayerId(UUID blackPlayerId) {
        this.blackPlayerId = blackPlayerId;
    }

    public UUID getWhitePlayerId() {
        return whitePlayerId;
    }

    public void setWhitePlayerId(UUID whitePlayerId) {
        this.whitePlayerId = whitePlayerId;
    }

    public OpponentType getOpponentType() {
        return opponentType;
    }

    public void setOpponentType(OpponentType opponentType) {
        this.opponentType = opponentType;
    }

    public BotSide getBotSide() {
        return botSide;
    }

    public void setBotSide(BotSide botSide) {
        this.botSide = botSide;
    }

    public BotDifficulty getBotDifficulty() {
        return botDifficulty;
    }

    public void setBotDifficulty(BotDifficulty botDifficulty) {
        this.botDifficulty = botDifficulty;
    }

    public long getBoardBlack() {
        return boardBlack;
    }

    public void setBoardBlack(long boardBlack) {
        this.boardBlack = boardBlack;
    }

    public long getBoardWhite() {
        return boardWhite;
    }

    public void setBoardWhite(long boardWhite) {
        this.boardWhite = boardWhite;
    }

    public Player getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(Player currentTurn) {
        this.currentTurn = currentTurn;
    }

    public int getConsecutivePasses() {
        return consecutivePasses;
    }

    public void setConsecutivePasses(int consecutivePasses) {
        this.consecutivePasses = consecutivePasses;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(UUID winnerId) {
        this.winnerId = winnerId;
    }

    public int getMoveCount() {
        return moveCount;
    }

    public void setMoveCount(int moveCount) {
        this.moveCount = moveCount;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
