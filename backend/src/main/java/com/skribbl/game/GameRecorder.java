package com.skribbl.game;

import java.util.Map;

/**
 * Persistence hooks, called by the engine at lifecycle boundaries.
 *
 * <p>Implementations must return quickly: they are invoked while the room lock
 * is held. The production implementation, {@code DbGameRecorder}, hands each
 * call to a single background thread, so MySQL latency never reaches the game.
 */
public interface GameRecorder {

    void gameStarted(Room room);

    void turnStarted(Room room, TurnRecord turn);

    void wordChosen(Room room, TurnRecord turn, String word);

    /** @param scores snapshot of player id to total score, taken under the lock */
    void turnEnded(Room room, TurnRecord turn, EndReason reason,
                   int correctGuessCount, Map<String, Integer> scores);

    void gameOver(Room room, Map<String, Integer> scores);

    void playerLeft(Room room, String playerId);

    void hostChanged(Room room, String newHostId);

    /**
     * A chat line or guess.
     *
     * @param turn the turn it was sent during, or null outside a drawing turn
     */
    void message(Room room, TurnRecord turn, String playerId, String text,
                 ChatKind kind, int points);

    /** A recorder that does nothing; handy in tests. */
    GameRecorder NO_OP = new GameRecorder() {
        @Override public void gameStarted(Room room) { }
        @Override public void turnStarted(Room room, TurnRecord turn) { }
        @Override public void wordChosen(Room room, TurnRecord turn, String word) { }
        @Override public void turnEnded(Room room, TurnRecord turn, EndReason reason,
                                        int correctGuessCount, Map<String, Integer> scores) { }
        @Override public void gameOver(Room room, Map<String, Integer> scores) { }
        @Override public void playerLeft(Room room, String playerId) { }
        @Override public void hostChanged(Room room, String newHostId) { }
        @Override public void message(Room room, TurnRecord turn, String playerId, String text,
                                      ChatKind kind, int points) { }
    };
}
