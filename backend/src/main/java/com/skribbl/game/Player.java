package com.skribbl.game;

import java.time.Instant;
import java.util.Objects;

/**
 * A participant in a room. One instance lives in memory per connected player
 * and is mirrored to the {@code players} table at round boundaries.
 *
 * <p>Identity is the generated {@code id}, not the display name: two people are
 * allowed to be called "Nutan" in the same room, and the WebSocket session is
 * keyed on the id.
 */
public class Player {

    private final String id;
    private String name;
    private final Instant joinedAt;

    /** Cumulative score across the whole game. */
    private int score;

    /** Points earned in the current round only; reset at each round start. */
    private int roundScore;

    private boolean host;

    /** False while the socket is dropped but the player may still reconnect. */
    private boolean connected = true;

    /** STOMP session id, used to target /user/queue destinations. */
    private String sessionId;

    /** Whether this player has already guessed the word in the current round. */
    private boolean guessedCurrentRound;

    /** Set when a player has taken a turn drawing, so turn rotation is fair. */
    private boolean hasDrawnThisCycle;

    public Player(String id, String name, boolean host) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.joinedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void addScore(int points) {
        this.score += points;
        this.roundScore += points;
    }

    public int getRoundScore() {
        return roundScore;
    }

    public void resetRoundScore() {
        this.roundScore = 0;
    }

    public boolean isHost() {
        return host;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean hasGuessedCurrentRound() {
        return guessedCurrentRound;
    }

    public void setGuessedCurrentRound(boolean guessedCurrentRound) {
        this.guessedCurrentRound = guessedCurrentRound;
    }

    public boolean hasDrawnThisCycle() {
        return hasDrawnThisCycle;
    }

    public void setHasDrawnThisCycle(boolean hasDrawnThisCycle) {
        this.hasDrawnThisCycle = hasDrawnThisCycle;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Player)) {
            return false;
        }
        return Objects.equals(id, ((Player) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Player{" + name + ", score=" + score + ", host=" + host + '}';
    }
}
