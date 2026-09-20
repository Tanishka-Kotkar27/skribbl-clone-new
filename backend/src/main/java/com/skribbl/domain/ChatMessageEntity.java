package com.skribbl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The {@code chat_messages} table: guesses, chat and system notices in one
 * stream. See {@link MessageType} for why guesses are not a separate table.
 *
 * <p>Both {@code round} and {@code player} are nullable: lobby chat happens
 * before any round exists, and system notices have no author.
 */
@Entity
@Table(
        name = "chat_messages",
        indexes = {
                @Index(name = "idx_chat_room", columnList = "room_id"),
                @Index(name = "idx_chat_round", columnList = "round_id")
        }
)
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private RoomEntity room;

    /** Null for lobby chat sent before the game started. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id")
    private RoundEntity round;

    /** Null for system-generated messages. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id")
    private PlayerEntity player;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MessageType type = MessageType.CHAT;

    /** Points awarded, when this message was a correct guess. */
    @Column(name = "points_awarded", nullable = false)
    private int pointsAwarded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessageEntity() {
        // Required by JPA.
    }

    public ChatMessageEntity(RoomEntity room, RoundEntity round, PlayerEntity player,
                             String content, MessageType type) {
        this.room = room;
        this.round = round;
        this.player = player;
        this.content = content;
        this.type = type;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public RoomEntity getRoom() {
        return room;
    }

    public RoundEntity getRound() {
        return round;
    }

    public void setRound(RoundEntity round) {
        this.round = round;
    }

    public PlayerEntity getPlayer() {
        return player;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public int getPointsAwarded() {
        return pointsAwarded;
    }

    public void setPointsAwarded(int pointsAwarded) {
        this.pointsAwarded = pointsAwarded;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "ChatMessageEntity{type=" + type + ", content=" + content + '}';
    }
}
