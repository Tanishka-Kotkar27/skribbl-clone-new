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
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The {@code rounds} table: one row per drawing turn.
 *
 * <p>Following skribbl.io, a "round" in the settings means one full cycle where
 * everybody draws once. This table records the individual <em>turns</em> inside
 * those cycles, which is why it carries both {@code roundNumber} (which cycle)
 * and {@code turnIndex} (position within it). A 3-round game with 4 players
 * produces 12 rows here.
 *
 * <p>The word is stored in clear text. That is safe: this table is history, read
 * only after the round has ended, and never serialized to a client mid-game.
 */
@Entity
@Table(
        name = "rounds",
        indexes = {
                @Index(name = "idx_rounds_room", columnList = "room_id"),
                @Index(name = "idx_rounds_drawer", columnList = "drawer_id")
        }
)
public class RoundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private RoomEntity room;

    /** Nullable: the drawer may have disconnected before the row is written. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drawer_id")
    private PlayerEntity drawer;

    @Column(name = "word", length = 100)
    private String word;

    /** Which cycle this turn belongs to, 1-based. */
    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    /** Position within the cycle, 0-based. */
    @Column(name = "turn_index", nullable = false)
    private int turnIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RoundStatus status = RoundStatus.CHOOSING;

    /** How many players guessed the word, for drawer scoring and stats. */
    @Column(name = "correct_guess_count", nullable = false)
    private int correctGuessCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected RoundEntity() {
        // Required by JPA.
    }

    public RoundEntity(RoomEntity room, PlayerEntity drawer, int roundNumber, int turnIndex) {
        this.room = room;
        this.drawer = drawer;
        this.roundNumber = roundNumber;
        this.turnIndex = turnIndex;
    }

    public Long getId() {
        return id;
    }

    public RoomEntity getRoom() {
        return room;
    }

    public PlayerEntity getDrawer() {
        return drawer;
    }

    public void setDrawer(PlayerEntity drawer) {
        this.drawer = drawer;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public RoundStatus getStatus() {
        return status;
    }

    public void setStatus(RoundStatus status) {
        this.status = status;
    }

    public int getCorrectGuessCount() {
        return correctGuessCount;
    }

    public void setCorrectGuessCount(int correctGuessCount) {
        this.correctGuessCount = correctGuessCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    @Override
    public String toString() {
        return "RoundEntity{round=" + roundNumber + ", turn=" + turnIndex
                + ", word=" + word + ", status=" + status + '}';
    }
}
