package com.skribbl.game;

/**
 * Everything the engine needs to say to the outside world.
 *
 * <p>An interface rather than a direct dependency on STOMP, so the engine can be
 * driven in tests by a fake that simply records what would have been sent. The
 * production implementation is {@code StompGameEvents}.
 */
public interface GameEvents {

    /** Sends to every player in the room. */
    void broadcast(Room room, String type, Object payload);

    /**
     * Sends to one player only. Used for the drawer's word choices and the
     * confirmed word, which must never reach the room topic.
     */
    void sendToPlayer(Room room, Player player, String type, Object payload);

    /** Pushes a full snapshot of the room (phase, players, masked word, clock). */
    void broadcastState(Room room);
}
