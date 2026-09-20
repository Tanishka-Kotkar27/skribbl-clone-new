package com.skribbl.domain;

import com.skribbl.game.GameSettings;
import com.skribbl.game.RoomStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code rooms} table.
 *
 * <p>This is the persisted record of a game, not the live object the game runs
 * from. {@link com.skribbl.game.Room} holds the in-memory state during play and
 * this entity is written at lifecycle boundaries — created on room creation,
 * updated when the game starts and finishes.
 *
 * <p>Keeping the two separate is deliberate. Making the live room an {@code @Entity}
 * would drag a persistence context into the hot path of every stroke and guess,
 * and would make the game logic impossible to unit test without a database.
 */
@Entity
@Table(
        name = "rooms",
        indexes = {
                @Index(name = "idx_rooms_code", columnList = "code", unique = true),
                @Index(name = "idx_rooms_status", columnList = "status")
        }
)
public class RoomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The shareable 6-character join code. */
    @Column(name = "code", nullable = false, unique = true, length = 6)
    private String code;

    /** Display name of whoever created the room. */
    @Column(name = "host_name", nullable = false, length = 20)
    private String hostName;

    /** In-memory UUID of the current host, so a promotion can be recorded. */
    @Column(name = "host_uid", length = 36)
    private String hostUid;

    /**
     * Host settings as JSON. See {@link GameSettingsConverter} for why this is a
     * string column rather than MySQL's native JSON type.
     */
    @Convert(converter = GameSettingsConverter.class)
    @Column(name = "settings_json", length = 4000)
    private GameSettings settings = new GameSettings();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RoomStatus status = RoomStatus.WAITING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Read-only navigation for history queries.
     *
     * <p>{@code mappedBy} means the child owns the foreign key, so adding a player
     * does not trigger an update on this row. No cascade: players are written
     * through their own repository, which keeps the write path explicit.
     */
    @OneToMany(mappedBy = "room", fetch = jakarta.persistence.FetchType.LAZY)
    private List<PlayerEntity> players = new ArrayList<>();

    @OneToMany(mappedBy = "room", fetch = jakarta.persistence.FetchType.LAZY)
    private List<RoundEntity> rounds = new ArrayList<>();

    protected RoomEntity() {
        // Required by JPA.
    }

    public RoomEntity(String code, String hostName, GameSettings settings) {
        this.code = code;
        this.hostName = hostName;
        this.settings = settings;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getHostUid() {
        return hostUid;
    }

    public void setHostUid(String hostUid) {
        this.hostUid = hostUid;
    }

    public GameSettings getSettings() {
        return settings;
    }

    public void setSettings(GameSettings settings) {
        this.settings = settings;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public List<PlayerEntity> getPlayers() {
        return players;
    }

    public List<RoundEntity> getRounds() {
        return rounds;
    }

    @Override
    public String toString() {
        return "RoomEntity{id=" + id + ", code=" + code + ", status=" + status + '}';
    }
}
