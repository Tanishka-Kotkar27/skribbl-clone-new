package com.skribbl.ws;

import com.skribbl.game.GameEvents;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.rest.dto.RoomStateResponse;
import org.springframework.stereotype.Component;

/**
 * Connects the transport-free {@link com.skribbl.game.GameEngine} to STOMP.
 *
 * <p>Room-wide events go to {@code /topic/room/{code}}; private ones go to the
 * player's own session queue. The engine decides which is which — this class
 * just delivers.
 */
@Component
public class StompGameEvents implements GameEvents {

    private final RoomBroadcaster broadcaster;

    public StompGameEvents(RoomBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void broadcast(Room room, String type, Object payload) {
        broadcaster.broadcast(room.getCode(), type, payload);
    }

    @Override
    public void sendToPlayer(Room room, Player player, String type, Object payload) {
        // No session means the player is mid-reconnect; they are re-sent their
        // private state by GameEngine.onPlayerConnected when they come back.
        if (player.getSessionId() != null) {
            broadcaster.sendToSession(player.getSessionId(), type, payload);
        }
    }

    @Override
    public void broadcastState(Room room) {
        broadcaster.broadcast(room.getCode(), EventType.GAME_STATE, RoomStateResponse.from(room));
    }
}
