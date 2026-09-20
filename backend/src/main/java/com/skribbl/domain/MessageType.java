package com.skribbl.domain;

/**
 * Discriminator for {@code chat_messages}.
 *
 * <p>The brief lists "guesses/chat_messages" as separate optional tables. They
 * are folded into one table here with this discriminator, because the two share
 * every column that matters (room, round, player, text, timestamp) and the
 * game's own chat panel renders them as a single interleaved stream. Two tables
 * would mean a UNION on every history read for no gain.
 */
public enum MessageType {
    /** Ordinary chat line. */
    CHAT,
    /** A guess that did not match the word. */
    GUESS,
    /** A guess that matched. The text is withheld from other guessers in the UI. */
    CORRECT_GUESS,
    /** Server-generated notice: player joined, round started, word revealed. */
    SYSTEM
}
