package com.skribbl.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single game room, held in memory for the life of the game.
 *
 * <p><strong>Responsibilities.</strong> {@code Room} owns <em>membership and the
 * shared canvas</em>: who is in the room, who hosts it, and the stroke history.
 * It deliberately does <em>not</em> own round/turn/scoring logic — that belongs to
 * {@link Game}, which this class composes. Keeping the split means the turn
 * rotation and scoring rules can be unit-tested without constructing a room full
 * of sockets.
 *
 * <p><strong>Thread safety.</strong> A room is touched from at least three kinds of
 * thread: the HTTP worker handling REST calls, the WebSocket inbound thread
 * delivering STOMP frames, and the scheduler thread firing round timers. Every
 * mutation therefore goes through {@link #lock()}. Callers must use the
 * {@code try { room.lock(); ... } finally { room.unlock(); }} idiom, or the
 * convenience {@link #withLock(Runnable)}.
 *
 * <p>Insertion order of {@code players} is preserved because turn rotation walks
 * the player list in join order, which is what skribbl.io does.
 */
public class Room {

    private final String code;
    private final Instant createdAt;
    private final GameSettings settings;
    private final ReentrantLock lock = new ReentrantLock();

    /** Database id, populated once the room is persisted (Phase 2). */
    private Long persistentId;

    private String hostId;
    private RoomStatus status = RoomStatus.WAITING;

    /** Insertion-ordered: turn rotation depends on join order. */
    private final Map<String, Player> players = new LinkedHashMap<>();

    /** Completed strokes for the current round. Powers undo and late-join replay. */
    private final List<Stroke> strokeHistory = new ArrayList<>();

    /**
     * Strokes the drawer has started but not yet finished (pointer still down).
     * Kept separately so undo only ever removes a <em>finished</em> stroke, and
     * so a player joining mid-stroke still sees the line being drawn.
     */
    private final Map<String, Stroke> activeStrokes = new LinkedHashMap<>();

    /** Total points on the canvas this turn, for the per-turn size cap. */
    private int canvasPointCount;

    /** The round/turn/scoring state machine for this room. */
    private final Game game;

    /** Last time anyone interacted; used to evict abandoned rooms. */
    private volatile Instant lastActivityAt = Instant.now();

    public Room(String code, GameSettings settings) {
        this.code = code;
        this.settings = settings;
        this.createdAt = Instant.now();
        this.game = new Game(this);
    }

    // ---------------------------------------------------------------- locking

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    /** Runs {@code action} while holding this room's lock. */
    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------- membership

    /**
     * Adds a player. The first player to arrive becomes the host.
     *
     * @return the created player, or empty if the room is full
     */
    public Optional<Player> addPlayer(String playerId, String name) {
        if (players.size() >= settings.getMaxPlayers()) {
            return Optional.empty();
        }
        boolean isFirst = players.isEmpty();
        Player player = new Player(playerId, name, isFirst);
        if (isFirst) {
            this.hostId = playerId;
        }
        players.put(playerId, player);
        touch();
        return Optional.of(player);
    }

    /**
     * Removes a player. If the host leaves, the longest-present remaining player
     * is promoted so the room does not become unstartable.
     *
     * @return the removed player, if they were present
     */
    public Optional<Player> removePlayer(String playerId) {
        Player removed = players.remove(playerId);
        if (removed == null) {
            return Optional.empty();
        }
        if (removed.isHost()) {
            promoteNewHost();
        }
        touch();
        return Optional.of(removed);
    }

    private void promoteNewHost() {
        Optional<Player> next = players.values().stream().findFirst();
        if (next.isPresent()) {
            Player newHost = next.get();
            newHost.setHost(true);
            this.hostId = newHost.getId();
        } else {
            this.hostId = null;
        }
    }

    public Optional<Player> getPlayer(String playerId) {
        return Optional.ofNullable(players.get(playerId));
    }

    /** Players in join order. */
    public List<Player> getPlayers() {
        return new ArrayList<>(players.values());
    }

    /** Only players whose socket is currently up. */
    public List<Player> getConnectedPlayers() {
        List<Player> out = new ArrayList<>();
        for (Player p : players.values()) {
            if (p.isConnected()) {
                out.add(p);
            }
        }
        return out;
    }

    public Collection<Player> playerView() {
        return players.values();
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isFull() {
        return players.size() >= settings.getMaxPlayers();
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    public boolean canStart() {
        return getConnectedPlayers().size() >= GameSettings.MIN_PLAYERS
                && status == RoomStatus.WAITING;
    }

    public boolean isHost(String playerId) {
        return hostId != null && hostId.equals(playerId);
    }

    // ---------------------------------------------------------- canvas history

    public void addStroke(Stroke stroke) {
        strokeHistory.add(stroke);
        canvasPointCount += stroke.getPoints().size();
        touch();
    }

    /** Registers a stroke the drawer has just started. */
    public void beginStroke(Stroke stroke) {
        activeStrokes.put(stroke.getId(), stroke);
        canvasPointCount += stroke.getPoints().size();
        touch();
    }

    public Optional<Stroke> getActiveStroke(String strokeId) {
        return Optional.ofNullable(activeStrokes.get(strokeId));
    }

    /** Moves a finished stroke from in-progress into the history. */
    public Optional<Stroke> finishStroke(String strokeId) {
        Stroke stroke = activeStrokes.remove(strokeId);
        if (stroke == null) {
            return Optional.empty();
        }
        strokeHistory.add(stroke);
        touch();
        return Optional.of(stroke);
    }

    /** Records points appended to an in-progress stroke, for the size cap. */
    public void countPoints(int added) {
        canvasPointCount += added;
    }

    public int getCanvasPointCount() {
        return canvasPointCount;
    }

    /** Removes and returns the most recent finished stroke, for undo. */
    public Optional<Stroke> undoLastStroke() {
        if (strokeHistory.isEmpty()) {
            return Optional.empty();
        }
        touch();
        Stroke removed = strokeHistory.remove(strokeHistory.size() - 1);
        canvasPointCount -= removed.getPoints().size();
        return Optional.of(removed);
    }

    public void clearCanvas() {
        strokeHistory.clear();
        activeStrokes.clear();
        canvasPointCount = 0;
        touch();
    }

    public int getStrokeCount() {
        return strokeHistory.size() + activeStrokes.size();
    }

    /** In-progress strokes, oldest first. Defensive copy. */
    public List<Stroke> getActiveStrokes() {
        return new ArrayList<>(activeStrokes.values());
    }

    /** Defensive copy, so a late joiner can be sent the canvas safely. */
    public List<Stroke> getStrokeHistory() {
        return new ArrayList<>(strokeHistory);
    }

    // -------------------------------------------------------------- accessors

    public String getCode() {
        return code;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public GameSettings getSettings() {
        return settings;
    }

    public Long getPersistentId() {
        return persistentId;
    }

    public void setPersistentId(Long persistentId) {
        this.persistentId = persistentId;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public Game getGame() {
        return game;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    @Override
    public String toString() {
        return "Room{" + code + ", players=" + players.size() + ", status=" + status + '}';
    }
}
