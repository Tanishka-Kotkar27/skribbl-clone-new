package com.skribbl.game;

import com.skribbl.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory home of every live room, keyed by room code.
 *
 * <p>This is the only place rooms are created or found, which keeps the "one
 * room object per code" invariant in a single class. A {@link ConcurrentHashMap}
 * is enough here because the map itself is only ever put-to and removed-from;
 * the mutable state inside each {@link Room} is guarded by that room's own lock.
 */
@Component
public class RoomRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);

    /** Ambiguous characters (0/O, 1/I) are excluded so codes are easy to read aloud. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_ATTEMPTS = 20;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AppProperties appProperties;

    public RoomRegistry(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** Creates a room with a fresh, unused code. */
    public Room createRoom(GameSettings settings) {
        String code = generateUniqueCode();
        Room room = new Room(code, settings);
        rooms.put(code, room);
        log.info("Created room {} with {}", code, settings);
        return room;
    }

    public Optional<Room> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rooms.get(code.toUpperCase()));
    }

    public void remove(String code) {
        Room removed = rooms.remove(code);
        if (removed != null) {
            removed.getGame().cancelTimers();
            log.info("Removed room {}", code);
        }
    }

    /** Public rooms that are still open to new players. */
    public List<Room> listJoinablePublicRooms() {
        List<Room> out = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (!room.getSettings().isPrivate()
                    && room.getStatus() == RoomStatus.WAITING
                    && !room.isFull()) {
                out.add(room);
            }
        }
        return out;
    }

    public Collection<Room> all() {
        return rooms.values();
    }

    public int size() {
        return rooms.size();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        // 32^6 is ~1e9 combinations; exhausting 20 attempts means something is
        // very wrong, so fail loudly rather than returning a duplicate code.
        throw new IllegalStateException("Could not generate a unique room code");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Drops rooms that have been empty longer than the configured TTL, so a
     * long-running server does not leak a Room per abandoned game.
     */
    @Scheduled(fixedDelay = 60_000L)
    public void evictAbandonedRooms() {
        Instant cutoff = Instant.now().minusSeconds(appProperties.getRoomTtlSeconds());
        List<String> doomed = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (room.isEmpty() && room.getLastActivityAt().isBefore(cutoff)) {
                doomed.add(room.getCode());
            }
        }
        for (String code : doomed) {
            remove(code);
        }
        if (!doomed.isEmpty()) {
            log.info("Evicted {} abandoned room(s)", doomed.size());
        }
    }
}
