package com.skribbl.ws.dto;

/**
 * Envelope for everything the server pushes.
 *
 * <p>A single envelope shape means the React client can have one subscription
 * per room and one switch statement on {@code type}, rather than a subscription
 * per event kind. The timestamp is useful for ordering and for measuring
 * round-trip latency during the demo.
 *
 * @param type    one of the constants in {@code EventType}
 * @param payload event-specific body, serialized as JSON
 * @param ts      server time in epoch milliseconds
 */
public record ServerEvent(String type, Object payload, long ts) {

    public static ServerEvent of(String type, Object payload) {
        return new ServerEvent(type, payload, System.currentTimeMillis());
    }
}
