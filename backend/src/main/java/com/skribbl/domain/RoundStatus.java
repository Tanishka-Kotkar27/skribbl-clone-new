package com.skribbl.domain;

/** Lifecycle of a single drawing turn, as persisted in {@code rounds.status}. */
public enum RoundStatus {
    /** Drawer is picking from their word choices. */
    CHOOSING,
    /** Word locked in, timer running. */
    DRAWING,
    /** Timer expired with at least one player still guessing. */
    TIMED_OUT,
    /** Every guesser got it, so the round ended early. */
    ALL_GUESSED,
    /** Drawer disconnected or the room was abandoned mid-turn. */
    ABANDONED
}
