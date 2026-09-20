package com.skribbl.game;

/**
 * The engine's fixed delays, in milliseconds. The draw time itself comes from
 * the room settings; these are the gaps around it.
 *
 * <p>Injectable so tests can run a whole game in a couple of seconds instead of
 * several minutes.
 *
 * @param choiceTimeoutMs   how long the drawer gets to pick; then a word is picked for them
 * @param roundEndPauseMs   how long the revealed word and scores stay up between turns
 * @param tickMs            how often the remaining time is broadcast
 * @param reconnectGraceMs  how long a dropped player keeps their seat before removal
 * @param chatCooldownMs    minimum gap between one player's chat messages
 */
public record EngineTimings(long choiceTimeoutMs,
                            long roundEndPauseMs,
                            long tickMs,
                            long reconnectGraceMs,
                            long chatCooldownMs) {

    /** Four-argument form with the default 300 ms chat cooldown. */
    public EngineTimings(long choiceTimeoutMs, long roundEndPauseMs, long tickMs, long reconnectGraceMs) {
        this(choiceTimeoutMs, roundEndPauseMs, tickMs, reconnectGraceMs, 300);
    }

    public static EngineTimings defaults() {
        return new EngineTimings(15_000, 5_000, 1_000, 15_000, 300);
    }
}
