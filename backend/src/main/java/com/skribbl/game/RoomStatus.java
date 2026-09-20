package com.skribbl.game;

/** Persisted room lifecycle status (mirrors the `rooms.status` column). */
public enum RoomStatus {
    WAITING,
    IN_PROGRESS,
    FINISHED,
    ABANDONED
}
