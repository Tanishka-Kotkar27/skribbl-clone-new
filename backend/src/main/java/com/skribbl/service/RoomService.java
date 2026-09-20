package com.skribbl.service;

import com.skribbl.config.AppProperties;
import com.skribbl.game.GameEngine;
import com.skribbl.game.GameSettings;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.RoomRegistry;
import com.skribbl.game.RoomStatus;
import com.skribbl.rest.dto.JoinRoomResponse;
import com.skribbl.rest.dto.RoomStateResponse;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Room lifecycle operations shared by the REST layer and (from Phase 3) the
 * game engine.
 *
 * <p>Every method that mutates a room takes that room's lock, so callers do not
 * have to remember to. Note where the database writes sit: <em>outside</em> the
 * lock, after the in-memory mutation is complete. Holding a room's lock across a
 * database round-trip would let a slow query block every stroke and guess in
 * that room.
 */
@Service
public class RoomService {

    private final RoomRegistry roomRegistry;
    private final AppProperties appProperties;
    private final PersistenceService persistenceService;
    private final GameEngine gameEngine;

    public RoomService(RoomRegistry roomRegistry,
                       AppProperties appProperties,
                       PersistenceService persistenceService,
                       GameEngine gameEngine) {
        this.roomRegistry = roomRegistry;
        this.appProperties = appProperties;
        this.persistenceService = persistenceService;
        this.gameEngine = gameEngine;
    }

    /** Creates a room, seats the host in it, and records both in MySQL. */
    public JoinRoomResponse createRoom(String hostName, GameSettings settings) {
        Room room = roomRegistry.createRoom(settings);

        Player host;
        JoinRoomResponse response;
        room.lock();
        try {
            host = room.addPlayer(newPlayerId(), hostName)
                    .orElseThrow(() -> new IllegalStateException("New room rejected its host"));
            response = buildJoinResponse(room, host);
        } finally {
            room.unlock();
        }

        persistenceService.saveNewRoom(room, host).ifPresent(room::setPersistentId);
        return response;
    }

    /**
     * Seats a new player in an existing room.
     *
     * @throws RoomNotFoundException if the code does not match a live room
     * @throws RoomUnavailableException if the room is full or already playing
     */
    public JoinRoomResponse joinRoom(String code, String playerName) {
        Room room = requireRoom(code);

        Player player;
        JoinRoomResponse response;
        room.lock();
        try {
            if (room.getStatus() != RoomStatus.WAITING) {
                throw new RoomUnavailableException("That game has already started");
            }
            player = room.addPlayer(newPlayerId(), playerName)
                    .orElseThrow(() -> new RoomUnavailableException("That room is full"));
            response = buildJoinResponse(room, player);
        } finally {
            room.unlock();
        }

        persistenceService.savePlayerJoined(room, player);
        return response;
    }

    /**
     * Host starts the game. Rule checks (host only, enough players, not already
     * running) live in the engine and surface as {@code GameActionException}.
     */
    public RoomStateResponse startGame(String code, String playerId) {
        Room room = requireRoom(code);
        gameEngine.startGame(room, playerId);
        return getRoomState(code);
    }

    /** A player leaves deliberately; no reconnect grace period applies. */
    public void leaveRoom(String code, String playerId) {
        Room room = requireRoom(code);
        gameEngine.removePlayer(room, playerId);
    }

    /** Current lobby/game state for a room. */
    public RoomStateResponse getRoomState(String code) {
        Room room = requireRoom(code);
        room.lock();
        try {
            return RoomStateResponse.from(room);
        } finally {
            room.unlock();
        }
    }

    public Room requireRoom(String code) {
        Optional<Room> room = roomRegistry.findByCode(code);
        if (room.isEmpty()) {
            throw new RoomNotFoundException("No room with code " + code);
        }
        return room.get();
    }

    private JoinRoomResponse buildJoinResponse(Room room, Player player) {
        return new JoinRoomResponse(
                room.getCode(),
                player.getId(),
                player.getName(),
                player.isHost(),
                buildJoinUrl(room.getCode()),
                RoomStateResponse.from(room));
    }

    private String buildJoinUrl(String code) {
        String base = appProperties.getFrontendUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/join/" + code;
    }

    private String newPlayerId() {
        return UUID.randomUUID().toString();
    }

    /** Thrown when a room code does not resolve. Mapped to HTTP 404. */
    public static class RoomNotFoundException extends RuntimeException {
        public RoomNotFoundException(String message) {
            super(message);
        }
    }

    /** Thrown when a room exists but cannot be joined. Mapped to HTTP 409. */
    public static class RoomUnavailableException extends RuntimeException {
        public RoomUnavailableException(String message) {
            super(message);
        }
    }
}
