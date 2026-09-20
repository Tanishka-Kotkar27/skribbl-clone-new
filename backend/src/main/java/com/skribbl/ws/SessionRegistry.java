package com.skribbl.ws;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a STOMP session id to the player and room it belongs to.
 *
 * <p>Without this, a disconnect event would tell us only that "some socket
 * closed" — we would have no way to work out which player left which room, and
 * the lobby would fill with ghosts. It is populated on join and read on
 * disconnect.
 */
@Component
public class SessionRegistry {

    /** What a socket is bound to. */
    public record Binding(String roomCode, String playerId, String playerName) {
    }

    private final Map<String, Binding> bySession = new ConcurrentHashMap<>();

    public void bind(String sessionId, String roomCode, String playerId, String playerName) {
        if (sessionId == null) {
            return;
        }
        bySession.put(sessionId, new Binding(roomCode, playerId, playerName));
    }

    public Optional<Binding> lookup(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySession.get(sessionId));
    }

    public Optional<Binding> unbind(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySession.remove(sessionId));
    }

    public int size() {
        return bySession.size();
    }
}
