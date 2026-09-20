package com.skribbl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The {@code players} table: one row per person per game.
 *
 * <p>Note the two identifiers. {@code id} is the database key; {@code playerUid}
 * is the UUID the live {@link com.skribbl.game.Player} carries and the browser
 * replays when it opens its socket. Both are needed — the UID is what links a
 * WebSocket session to a row, and it is generated before any row exists.
 */
@Entity
@Table(
        name = "players",
        indexes = {
                @Index(name = "idx_players_room", columnList = "room_id"),
                @Index(name = "idx_players_uid", columnList = "player_uid")
        }
)
public class PlayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * LAZY because loading a player should not drag the whole room with it.
     * All access happens inside a transaction, and {@code open-in-view} is off,
     * so a lazy proxy never escapes to the view layer.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private RoomEntity room;

    /** The in-memory player id (UUID). */
    @Column(name = "player_uid", nullable = false, length = 36)
    private String playerUid;

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "is_host", nullable = false)
    private boolean host;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    protected PlayerEntity() {
        // Required by JPA.
    }

    public PlayerEntity(RoomEntity room, String playerUid, String name, boolean host) {
        this.room = room;
        this.playerUid = playerUid;
        this.name = name;
        this.host = host;
    }

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public RoomEntity getRoom() {
        return room;
    }

    public String getPlayerUid() {
        return playerUid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public boolean isHost() {
        return host;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(Instant leftAt) {
        this.leftAt = leftAt;
    }

    @Override
    public String toString() {
        return "PlayerEntity{id=" + id + ", name=" + name + ", score=" + score + '}';
    }
}
