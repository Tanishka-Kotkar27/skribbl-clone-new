/**
 * JPA entities backing the five game tables.
 *
 * <p>These mirror the in-memory {@code com.skribbl.game} classes rather than
 * replacing them. The game runs from memory during a round and persists at
 * round boundaries, so the hot path — strokes, guesses, timer ticks — never
 * waits on the database.
 *
 * <ul>
 *   <li>{@link com.skribbl.domain.RoomEntity} &mdash; {@code rooms}</li>
 *   <li>{@link com.skribbl.domain.PlayerEntity} &mdash; {@code players}</li>
 *   <li>{@link com.skribbl.domain.RoundEntity} &mdash; {@code rounds}, one row per drawing turn</li>
 *   <li>{@link com.skribbl.domain.WordEntity} &mdash; {@code words}, the word pool</li>
 *   <li>{@link com.skribbl.domain.ChatMessageEntity} &mdash; {@code chat_messages}, chat and guesses</li>
 * </ul>
 */
package com.skribbl.domain;
