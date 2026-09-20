package com.skribbl.game;

import com.skribbl.ws.EventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.skribbl.game.GameViews.payload;

/**
 * Receives the drawer's strokes and relays them to the room.
 *
 * <h2>The stroke protocol</h2>
 * <pre>
 *  pointerdown ─▶ draw-start { strokeId, x, y, color, size, eraser }
 *  pointermove ─▶ draw-move  { strokeId, points: [{x,y}, …] }    (batched ~every 30 ms)
 *  pointerup   ─▶ draw-end   { strokeId }
 * </pre>
 * Each is relayed to the whole room as one {@code draw_data} event, tagged with
 * {@code kind: start | move | end}, so viewers can draw each new segment as it
 * arrives rather than repainting the whole canvas.
 *
 * <h2>Why the server checks and cleans everything</h2>
 * The browser is not trusted. Every stroke message is checked against the game
 * state (it must come from the current drawer, while drawing), and every value
 * is clamped before it is stored or relayed: coordinates to 0–1, brush size to a
 * sane range, colour to a {@code #rrggbb} pattern. Hard caps on points per
 * message, per stroke and per turn stop one modified client from pushing
 * megabytes at everyone else in the room.
 *
 * <p>Like {@link GameEngine}, this class has no Spring or socket imports and is
 * tested directly with a fake {@link GameEvents}.
 */
public final class DrawingService {

    /** Largest batch accepted in one draw-move message. */
    public static final int MAX_POINTS_PER_BATCH = 500;
    /** Longest single stroke; beyond this, further points are dropped. */
    public static final int MAX_POINTS_PER_STROKE = 5_000;
    /** Total ink allowed on the canvas in one turn. */
    public static final int MAX_POINTS_PER_TURN = 20_000;
    /** Most strokes allowed in one turn. */
    public static final int MAX_STROKES_PER_TURN = 3_000;

    public static final int MIN_BRUSH = 1;
    public static final int MAX_BRUSH = 60;
    public static final String DEFAULT_COLOR = "#000000";

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int MAX_STROKE_ID_LENGTH = 64;

    private final GameEvents events;

    public DrawingService(GameEvents events) {
        this.events = events;
    }

    // =====================================================================
    // Strokes
    // =====================================================================

    /**
     * The drawer put the pen down.
     *
     * @return {@code false} if the message was rejected (not the drawer, not
     *         drawing, bad id, or a cap reached); rejected messages are dropped
     *         silently rather than answered, since a stream of error replies
     *         during a scribble would only add to the noise
     */
    public boolean startStroke(Room room, String playerId, String strokeId,
                               double x, double y, String color, int size, boolean eraser) {
        room.lock();
        try {
            if (!canDraw(room, playerId) || !validStrokeId(strokeId)
                    || room.getActiveStroke(strokeId).isPresent()
                    || room.getStrokeCount() >= MAX_STROKES_PER_TURN
                    || room.getCanvasPointCount() >= MAX_POINTS_PER_TURN) {
                return false;
            }

            Stroke stroke = new Stroke(strokeId, playerId, cleanColor(color), clampSize(size), eraser);
            stroke.addPoint(clamp01(x), clamp01(y));
            room.beginStroke(stroke);

            events.broadcast(room, EventType.DRAW_DATA, payload(
                    "kind", "start",
                    "strokeId", strokeId,
                    "playerId", playerId,
                    "color", stroke.getColor(),
                    "size", stroke.getSize(),
                    "eraser", stroke.isEraser(),
                    "points", pointViews(stroke.getPoints())));
            return true;
        } finally {
            room.unlock();
        }
    }

    /**
     * More points along the current stroke.
     *
     * @return the number of points accepted (0 if the whole batch was rejected)
     */
    public int addPoints(Room room, String playerId, String strokeId, List<Stroke.Point> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        room.lock();
        try {
            if (!canDraw(room, playerId)) {
                return 0;
            }
            Stroke stroke = room.getActiveStroke(strokeId).orElse(null);
            if (stroke == null || !playerId.equals(stroke.getPlayerId())) {
                return 0;
            }

            int leftInStroke = MAX_POINTS_PER_STROKE - stroke.getPoints().size();
            int leftInTurn = MAX_POINTS_PER_TURN - room.getCanvasPointCount();
            int allowed = Math.min(Math.min(points.size(), MAX_POINTS_PER_BATCH),
                    Math.min(leftInStroke, leftInTurn));
            if (allowed <= 0) {
                return 0;
            }

            List<Stroke.Point> accepted = new ArrayList<>(allowed);
            for (int i = 0; i < allowed; i++) {
                Stroke.Point p = points.get(i);
                if (p == null) {
                    continue;
                }
                Stroke.Point clean = new Stroke.Point(clamp01(p.getX()), clamp01(p.getY()));
                stroke.getPoints().add(clean);
                accepted.add(clean);
            }
            if (accepted.isEmpty()) {
                return 0;
            }
            room.countPoints(accepted.size());

            events.broadcast(room, EventType.DRAW_DATA, payload(
                    "kind", "move",
                    "strokeId", strokeId,
                    "playerId", playerId,
                    "points", pointViews(accepted)));
            return accepted.size();
        } finally {
            room.unlock();
        }
    }

    /** The drawer lifted the pen. */
    public boolean endStroke(Room room, String playerId, String strokeId) {
        room.lock();
        try {
            Stroke stroke = room.getActiveStroke(strokeId).orElse(null);
            // Allowed even if the turn has just ended, so a stroke in flight
            // when the timer runs out is closed cleanly rather than left open.
            if (stroke == null || !stroke.getPlayerId().equals(playerId)) {
                return false;
            }
            room.finishStroke(strokeId);
            events.broadcast(room, EventType.DRAW_DATA, payload(
                    "kind", "end",
                    "strokeId", strokeId,
                    "playerId", playerId));
            return true;
        } finally {
            room.unlock();
        }
    }

    // =====================================================================
    // Undo and clear
    // =====================================================================

    /**
     * Removes the drawer's most recent finished stroke.
     *
     * <p>The server decides which stroke is undone and announces its id, and
     * every client — the drawer included — removes that id and repaints. Letting
     * each client undo its own idea of "the last stroke" would let canvases drift
     * apart the moment a message arrived out of step.
     */
    public boolean undo(Room room, String playerId) {
        room.lock();
        try {
            if (!canDraw(room, playerId)) {
                return false;
            }
            return room.undoLastStroke().map(removed -> {
                events.broadcast(room, EventType.DRAW_UNDO, payload("strokeId", removed.getId()));
                return true;
            }).orElse(false);
        } finally {
            room.unlock();
        }
    }

    /** Wipes the canvas. Drawer only. */
    public boolean clear(Room room, String playerId) {
        room.lock();
        try {
            if (!canDraw(room, playerId)) {
                return false;
            }
            room.clearCanvas();
            events.broadcast(room, EventType.CANVAS_CLEAR, payload());
            return true;
        } finally {
            room.unlock();
        }
    }

    // =====================================================================
    // Late joiners
    // =====================================================================

    /**
     * Sends the current drawing to one player — someone who just joined or
     * refreshed mid-turn — so they see what everyone else sees instead of a blank
     * canvas that only fills in from the next stroke onward.
     */
    public void replayTo(Room room, Player player) {
        room.lock();
        try {
            GamePhase phase = room.getGame().getPhase();
            if (phase != GamePhase.DRAWING && phase != GamePhase.ROUND_END) {
                return;
            }
            List<Map<String, Object>> strokes = new ArrayList<>();
            for (Stroke s : room.getStrokeHistory()) {
                strokes.add(strokeView(s, true));
            }
            for (Stroke s : room.getActiveStrokes()) {
                strokes.add(strokeView(s, false));
            }
            events.sendToPlayer(room, player, EventType.CANVAS_REPLAY, payload("strokes", strokes));
        } finally {
            room.unlock();
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private boolean canDraw(Room room, String playerId) {
        Game game = room.getGame();
        return playerId != null
                && game.getPhase() == GamePhase.DRAWING
                && playerId.equals(game.getDrawerId());
    }

    private static boolean validStrokeId(String id) {
        return id != null && !id.isBlank() && id.length() <= MAX_STROKE_ID_LENGTH;
    }

    /**
     * Clamps to 0–1 and rounds to 4 decimal places. Four places is sub-pixel on
     * any real screen (1/10000 of the width), and it roughly halves the JSON size
     * of every point compared with a full double.
     */
    static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, v));
        return Math.round(clamped * 10_000.0) / 10_000.0;
    }

    static int clampSize(int size) {
        return Math.max(MIN_BRUSH, Math.min(MAX_BRUSH, size));
    }

    static String cleanColor(String color) {
        return color != null && COLOR.matcher(color).matches() ? color.toLowerCase() : DEFAULT_COLOR;
    }

    /** A snapshot copy, so later points added to the live stroke do not leak into a sent payload. */
    private static List<Map<String, Object>> pointViews(List<Stroke.Point> points) {
        List<Map<String, Object>> out = new ArrayList<>(points.size());
        for (Stroke.Point p : points) {
            out.add(payload("x", p.getX(), "y", p.getY()));
        }
        return out;
    }

    private static Map<String, Object> strokeView(Stroke s, boolean complete) {
        return payload(
                "id", s.getId(),
                "playerId", s.getPlayerId(),
                "color", s.getColor(),
                "size", s.getSize(),
                "eraser", s.isEraser(),
                "complete", complete,
                "points", pointViews(s.getPoints()));
    }
}
