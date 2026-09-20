package com.skribbl.game;

import java.util.ArrayList;
import java.util.List;

/**
 * One continuous pen stroke: everything between a mousedown and the matching
 * mouseup.
 *
 * <p>Coordinates are stored <strong>normalised to 0.0&ndash;1.0</strong> relative to
 * the canvas, never in pixels. Clients run at different canvas sizes, so pixel
 * coordinates would render at the wrong place on every screen but the drawer's.
 * Each client multiplies by its own canvas width/height at render time.
 *
 * <p>The room keeps an ordered list of these as its stroke history, which gives
 * undo (pop the last one) and late-joiner replay (send the whole list) for free.
 */
public class Stroke {

    /** A single sampled point along the stroke. */
    public static class Point {
        private double x;
        private double y;

        public Point() {
        }

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }
    }

    private String id;
    private String playerId;
    private String color = "#000000";
    private int size = 4;
    private boolean eraser;
    private List<Point> points = new ArrayList<>();

    public Stroke() {
    }

    public Stroke(String id, String playerId, String color, int size, boolean eraser) {
        this.id = id;
        this.playerId = playerId;
        this.color = color;
        this.size = size;
        this.eraser = eraser;
    }

    public void addPoint(double x, double y) {
        this.points.add(new Point(x, y));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public boolean isEraser() {
        return eraser;
    }

    public void setEraser(boolean eraser) {
        this.eraser = eraser;
    }

    public List<Point> getPoints() {
        return points;
    }

    public void setPoints(List<Point> points) {
        this.points = points == null ? new ArrayList<>() : points;
    }
}
