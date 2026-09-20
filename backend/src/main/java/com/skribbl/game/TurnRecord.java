package com.skribbl.game;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Identity of one drawing turn, created when the turn begins.
 *
 * <p>Two jobs:
 * <ul>
 *   <li>{@code turnId} is a monotonically increasing token. Every scheduled
 *       callback captures the id of the turn it belongs to and does nothing if
 *       the room has since moved on. Cancelling a {@code ScheduledFuture} is not
 *       enough on its own: a callback that has <em>already started</em> running
 *       and is blocked waiting for the room lock cannot be cancelled, and without
 *       this check it would end the next turn early.</li>
 *   <li>{@code dbId} is filled in asynchronously once the {@code rounds} row has
 *       been written, so later writes for the same turn (word chosen, turn
 *       ended) can find it without the game thread ever waiting on MySQL.</li>
 * </ul>
 */
public final class TurnRecord {

    private final long turnId;
    private final String drawerId;
    private final int roundNumber;
    private final int turnIndex;
    private final AtomicReference<Long> dbId = new AtomicReference<>();

    public TurnRecord(long turnId, String drawerId, int roundNumber, int turnIndex) {
        this.turnId = turnId;
        this.drawerId = drawerId;
        this.roundNumber = roundNumber;
        this.turnIndex = turnIndex;
    }

    public long getTurnId() {
        return turnId;
    }

    public String getDrawerId() {
        return drawerId;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public AtomicReference<Long> getDbId() {
        return dbId;
    }
}
