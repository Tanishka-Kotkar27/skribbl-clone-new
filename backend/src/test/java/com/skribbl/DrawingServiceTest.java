package com.skribbl;

import com.skribbl.game.DrawingService;
import com.skribbl.game.GameEvents;
import com.skribbl.game.GameSettings;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.Stroke;
import com.skribbl.ws.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for stroke relay: who may draw, how input is cleaned, the size caps,
 * undo/clear semantics and late-join replay.
 */
class DrawingServiceTest {

    record Sent(String type, String target, Map<String, Object> payload) {
    }

    static class RecordingEvents implements GameEvents {
        final List<Sent> sent = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public void broadcast(Room room, String type, Object payload) {
            sent.add(new Sent(type, null, (Map<String, Object>) payload));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void sendToPlayer(Room room, Player player, String type, Object payload) {
            sent.add(new Sent(type, player.getId(), (Map<String, Object>) payload));
        }

        @Override
        public void broadcastState(Room room) {
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }
    }

    private RecordingEvents events;
    private DrawingService drawing;
    private Room room;

    @BeforeEach
    void setUp() {
        events = new RecordingEvents();
        drawing = new DrawingService(events);
        room = new Room("DRAW01", new GameSettings());
        room.addPlayer("p0", "Asha");
        room.addPlayer("p1", "Bilal");
        room.getGame().start();
        room.getGame().beginTurn();          // p0 draws
        room.getGame().confirmWord("rocket");
    }

    private static List<Stroke.Point> points(double... xy) {
        List<Stroke.Point> out = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            out.add(new Stroke.Point(xy[i], xy[i + 1]));
        }
        return out;
    }

    @Test
    @DisplayName("only the current drawer can draw, undo or clear; rejections broadcast nothing")
    void onlyTheDrawerCanDraw() {
        assertFalse(drawing.startStroke(room, "p1", "s1", .5, .5, "#ff0000", 4, false));
        assertFalse(drawing.undo(room, "p1"));
        assertFalse(drawing.clear(room, "p1"));
        assertTrue(events.sent.isEmpty());

        assertTrue(drawing.startStroke(room, "p0", "s1", .5, .5, "#ff0000", 4, false));
        assertEquals(0, drawing.addPoints(room, "p1", "s1", points(.6, .6)));
    }

    @Test
    @DisplayName("a stroke is relayed as start, move, end and lands in the history")
    void strokeLifecycle() {
        drawing.startStroke(room, "p0", "s1", .1, .2, "#FF0000", 6, false);
        assertEquals("start", events.last().payload().get("kind"));
        assertEquals("#ff0000", events.last().payload().get("color"));
        assertEquals("p0", events.last().payload().get("playerId"));
        assertNull(events.last().target(), "strokes go to the whole room");

        assertEquals(3, drawing.addPoints(room, "p0", "s1", points(.2, .3, .3, .4, .4, .5)));
        assertEquals(3, ((List<?>) events.last().payload().get("points")).size());
        assertTrue(room.getStrokeHistory().isEmpty(), "not finished yet");

        assertTrue(drawing.endStroke(room, "p0", "s1"));
        assertEquals("end", events.last().payload().get("kind"));
        assertEquals(4, room.getStrokeHistory().get(0).getPoints().size());
        assertEquals(0, drawing.addPoints(room, "p0", "s1", points(.9, .9)));
    }

    @Test
    @DisplayName("coordinates, brush size and colour are cleaned before relay")
    @SuppressWarnings("unchecked")
    void inputIsCleaned() {
        drawing.startStroke(room, "p0", "s1", -3.0, 9.5, "red; drop table", 999, false);
        Map<String, Object> start = events.last().payload();
        Map<String, Object> first = ((List<Map<String, Object>>) start.get("points")).get(0);
        assertEquals(0.0, first.get("x"));
        assertEquals(1.0, first.get("y"));
        assertEquals("#000000", start.get("color"));
        assertEquals(DrawingService.MAX_BRUSH, start.get("size"));

        drawing.addPoints(room, "p0", "s1", points(0.123456789, 0.987654321, Double.NaN, 0.5));
        List<Map<String, Object>> moved = (List<Map<String, Object>>) events.last().payload().get("points");
        assertEquals(0.1235, moved.get(0).get("x"));
        assertEquals(0.0, moved.get(1).get("x"), "NaN must not reach other clients");

        assertFalse(drawing.startStroke(room, "p0", "x".repeat(65), .5, .5, null, 4, false));
    }

    @Test
    @DisplayName("per-batch, per-stroke and per-turn caps hold")
    void capsHold() {
        List<Stroke.Point> huge = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            huge.add(new Stroke.Point(.5, .5));
        }
        drawing.startStroke(room, "p0", "big", .5, .5, null, 4, false);
        assertEquals(DrawingService.MAX_POINTS_PER_BATCH, drawing.addPoints(room, "p0", "big", huge));
        for (int i = 0; i < 20; i++) {
            drawing.addPoints(room, "p0", "big", huge);
        }
        assertEquals(DrawingService.MAX_POINTS_PER_STROKE,
                room.getActiveStroke("big").orElseThrow().getPoints().size());

        int id = 0;
        while (room.getCanvasPointCount() < DrawingService.MAX_POINTS_PER_TURN && id < 100) {
            String strokeId = "f" + id++;
            if (!drawing.startStroke(room, "p0", strokeId, .1, .1, null, 4, false)) {
                break;
            }
            for (int k = 0; k < 12; k++) {
                drawing.addPoints(room, "p0", strokeId, huge);
            }
            drawing.endStroke(room, "p0", strokeId);
        }
        assertEquals(DrawingService.MAX_POINTS_PER_TURN, room.getCanvasPointCount());
        assertFalse(drawing.startStroke(room, "p0", "over", .1, .1, null, 4, false));
    }

    @Test
    @DisplayName("undo removes the last finished stroke, never the one still being drawn")
    void undoTargetsLastFinishedStroke() {
        for (String id : List.of("s1", "s2", "s3")) {
            drawing.startStroke(room, "p0", id, .5, .5, null, 4, false);
            drawing.endStroke(room, "p0", id);
        }
        drawing.startStroke(room, "p0", "live", .5, .5, null, 4, false);

        assertTrue(drawing.undo(room, "p0"));
        assertEquals(EventType.DRAW_UNDO, events.last().type());
        assertEquals("s3", events.last().payload().get("strokeId"));
        assertTrue(room.getActiveStroke("live").isPresent());

        drawing.undo(room, "p0");
        drawing.undo(room, "p0");
        int before = events.sent.size();
        assertFalse(drawing.undo(room, "p0"));
        assertEquals(before, events.sent.size(), "empty undo broadcasts nothing");
    }

    @Test
    @DisplayName("clear wipes the canvas for everyone")
    void clearWipesEverything() {
        drawing.startStroke(room, "p0", "s1", .5, .5, null, 4, false);
        drawing.endStroke(room, "p0", "s1");
        drawing.startStroke(room, "p0", "s2", .5, .5, null, 4, false);

        assertTrue(drawing.clear(room, "p0"));
        assertEquals(EventType.CANVAS_CLEAR, events.last().type());
        assertTrue(room.getStrokeHistory().isEmpty());
        assertTrue(room.getActiveStrokes().isEmpty());
        assertEquals(0, room.getCanvasPointCount());
    }

    @Test
    @DisplayName("a late joiner is sent the drawing so far, privately, as a snapshot")
    @SuppressWarnings("unchecked")
    void lateJoinReplay() {
        drawing.startStroke(room, "p0", "done", .1, .1, "#0000ff", 8, false);
        drawing.endStroke(room, "p0", "done");
        drawing.startStroke(room, "p0", "live", .3, .3, null, 4, false);

        drawing.replayTo(room, room.getPlayer("p1").orElseThrow());
        Sent replay = events.last();
        assertEquals(EventType.CANVAS_REPLAY, replay.type());
        assertEquals("p1", replay.target());

        List<Map<String, Object>> strokes = (List<Map<String, Object>>) replay.payload().get("strokes");
        assertEquals(List.of("done", "live"), strokes.stream().map(s -> s.get("id")).toList());
        assertEquals(true, strokes.get(0).get("complete"));
        assertEquals(false, strokes.get(1).get("complete"));

        drawing.addPoints(room, "p0", "live", points(.9, .9));
        assertEquals(1, ((List<?>) strokes.get(1).get("points")).size(), "payload must be a snapshot");
    }

    @Test
    @DisplayName("a new turn starts on a blank canvas with the new drawer holding the pen")
    void newTurnResetsCanvas() {
        drawing.startStroke(room, "p0", "s1", .5, .5, null, 4, false);
        drawing.endStroke(room, "p0", "s1");

        room.getGame().advanceTurn();
        room.getGame().beginTurn();          // p1 draws
        room.getGame().confirmWord("zebra");

        assertTrue(room.getStrokeHistory().isEmpty());
        assertEquals(0, room.getCanvasPointCount());
        assertFalse(drawing.startStroke(room, "p0", "s9", .5, .5, null, 4, false));
        assertTrue(drawing.startStroke(room, "p1", "s9", .5, .5, null, 4, false));
    }
}
