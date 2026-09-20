package com.skribbl.ws;

import com.skribbl.game.GameEngine;
import com.skribbl.game.Room;
import com.skribbl.game.RoomRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Optional;

/**
 * Turns raw socket lifecycle events into game-level consequences.
 *
 * <p>A closed browser tab produces a {@link SessionDisconnectEvent} and nothing
 * else. This listener maps the session back to its player via
 * {@link SessionRegistry} and hands it to the engine, which starts a short grace
 * period rather than removing the player outright — so a page refresh or a
 * wobbly connection does not throw someone out of the game. If they are not back
 * when the grace period ends, the engine removes them and, if they were drawing,
 * ends the turn.
 */
@Component
public class StompEventListener {

    private static final Logger log = LoggerFactory.getLogger(StompEventListener.class);

    private final SessionRegistry sessionRegistry;
    private final RoomRegistry roomRegistry;
    private final GameEngine gameEngine;

    public StompEventListener(SessionRegistry sessionRegistry,
                              RoomRegistry roomRegistry,
                              GameEngine gameEngine) {
        this.sessionRegistry = sessionRegistry;
        this.roomRegistry = roomRegistry;
        this.gameEngine = gameEngine;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.debug("STOMP session connected: {}", accessor.getSessionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Optional<SessionRegistry.Binding> binding = sessionRegistry.unbind(sessionId);
        if (binding.isEmpty()) {
            return;
        }
        Optional<Room> room = roomRegistry.findByCode(binding.get().roomCode());
        if (room.isEmpty()) {
            return;
        }
        // Pass the session id so the engine can ignore a late close from an old
        // socket after the same player has already reconnected on a new one.
        gameEngine.onPlayerDisconnected(room.get(), binding.get().playerId(), sessionId);
        log.debug("Player {} disconnected from room {}",
                binding.get().playerName(), binding.get().roomCode());
    }
}
