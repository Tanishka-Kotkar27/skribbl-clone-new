package com.skribbl.game;

/** How a chat line was classified, for history. */
public enum ChatKind {
    /** Ordinary chat: lobby, between turns, the drawer, or players who already guessed. */
    CHAT,
    /** A wrong guess from a player still guessing. */
    GUESS,
    /** The right answer. */
    CORRECT_GUESS
}
