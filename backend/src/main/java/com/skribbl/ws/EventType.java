package com.skribbl.ws;

/**
 * Every event name that crosses the WebSocket, in one place.
 *
 * <p>These strings are duplicated in {@code frontend/src/types/events.ts}. They
 * are the wire contract between the two halves of the app, so if you change one
 * you must change both — that is the one piece of duplication in the project
 * that is deliberate, because the alternative (code generation) is not worth the
 * setup cost at this size.
 */
public final class EventType {

    private EventType() {
    }

    // ---- Room & lobby (server -> clients) ----
    public static final String ROOM_STATE = "room_state";
    public static final String PLAYER_JOINED = "player_joined";
    public static final String PLAYER_LEFT = "player_left";
    public static final String HOST_CHANGED = "host_changed";

    // ---- Game state (server -> clients) ----
    public static final String GAME_STATE = "game_state";
    public static final String ROUND_START = "round_start";
    public static final String WORD_CHOICES = "word_choices";
    public static final String WORD_CONFIRMED = "word_confirmed";
    public static final String TIMER_TICK = "timer_tick";
    public static final String HINT_REVEALED = "hint_revealed";
    public static final String ROUND_END = "round_end";
    public static final String GAME_OVER = "game_over";

    // ---- Drawing (bidirectional) ----
    public static final String DRAW_START = "draw_start";
    public static final String DRAW_MOVE = "draw_move";
    public static final String DRAW_END = "draw_end";
    public static final String DRAW_DATA = "draw_data";
    public static final String CANVAS_CLEAR = "canvas_clear";
    public static final String DRAW_UNDO = "draw_undo";
    public static final String CANVAS_REPLAY = "canvas_replay";

    // ---- Chat & guessing ----
    public static final String GUESS = "guess";
    public static final String GUESS_RESULT = "guess_result";
    public static final String CHAT = "chat";
    public static final String CHAT_MESSAGE = "chat_message";
    public static final String SYSTEM_MESSAGE = "system_message";

    // ---- Infrastructure ----
    public static final String PONG = "pong";
    public static final String ERROR = "error";
}
