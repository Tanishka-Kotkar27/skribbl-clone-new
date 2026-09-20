package com.skribbl.rest.dto;

/**
 * Handed back after create or join. The client stores {@code playerId} and
 * replays it when it opens the WebSocket, which is how a socket is tied to a
 * seat in the room and how a refresh can rejoin the same seat.
 */
public record JoinRoomResponse(
        String roomCode,
        String playerId,
        String playerName,
        boolean host,
        String joinUrl,
        RoomStateResponse room
) {
}
