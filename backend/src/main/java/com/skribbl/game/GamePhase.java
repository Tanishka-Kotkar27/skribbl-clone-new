package com.skribbl.game;

/**
 * The room's position in the game lifecycle.
 *
 * <pre>
 *   LOBBY ──start──▶ CHOOSING ──word picked──▶ DRAWING
 *                        ▲                        │
 *                        │                 time up / all guessed
 *                        │                        ▼
 *                        └──more rounds left── ROUND_END ──last round──▶ GAME_OVER
 * </pre>
 */
public enum GamePhase {
    /** Players are gathering; host has not pressed Start. */
    LOBBY,
    /** A drawer has been selected and is picking one of N word choices. */
    CHOOSING,
    /** The word is locked in, the timer is running, guessing is open. */
    DRAWING,
    /** Round finished; the word is revealed and scores are shown briefly. */
    ROUND_END,
    /** All rounds played; final leaderboard and winner are shown. */
    GAME_OVER
}
