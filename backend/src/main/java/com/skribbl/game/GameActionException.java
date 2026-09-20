package com.skribbl.game;

/**
 * A player tried to do something the rules do not allow right now. Carries a
 * reason code so the REST layer can map it to the right HTTP status and the
 * WebSocket layer can send it back to just that player.
 */
public class GameActionException extends RuntimeException {

    public enum Reason {
        NOT_HOST,
        NOT_ENOUGH_PLAYERS,
        ALREADY_STARTED,
        NOT_DRAWER,
        WRONG_PHASE,
        INVALID_WORD
    }

    private final Reason reason;

    public GameActionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
