package com.skribbl.game;

/** Why a drawing turn ended. */
public enum EndReason {
    /** The draw timer ran out. */
    TIME_UP,
    /** Every connected guesser found the word, so the turn ended early. */
    ALL_GUESSED,
    /** The drawer left the room mid-turn. */
    DRAWER_LEFT
}
