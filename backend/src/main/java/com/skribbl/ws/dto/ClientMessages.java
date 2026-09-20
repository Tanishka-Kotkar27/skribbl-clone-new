package com.skribbl.ws.dto;

import java.util.List;

/**
 * Inbound message bodies, grouped so the wire contract reads top to bottom.
 *
 * <p>Records give immutable DTOs with Jackson support and no boilerplate.
 */
public final class ClientMessages {

    private ClientMessages() {
    }

    /** Sent immediately after subscribing, to bind this socket to a player. */
    public record Join(String roomCode, String playerId, String playerName) {
    }

    /** Drawer pressed the pointer down and began a stroke. */
    public record DrawStart(String strokeId, double x, double y,
                            String color, int size, boolean eraser) {
    }

    /**
     * A batch of sampled points along the current stroke. Batched rather than
     * one message per pointermove, which would flood the socket.
     */
    public record DrawMove(String strokeId, List<Point> points) {
    }

    /** Normalised canvas coordinate, 0.0 to 1.0 on both axes. */
    public record Point(double x, double y) {
    }

    /** Drawer released the pointer. */
    public record DrawEnd(String strokeId) {
    }

    /** Drawer picked one of the offered words. */
    public record WordChosen(String word) {
    }

    /** A guess or a chat line; the server decides which it is. */
    public record Chat(String text) {
    }

    /** Liveness probe used by the Phase 1 connection smoke test. */
    public record Ping(String text) {
    }
}
