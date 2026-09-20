package com.skribbl.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-map views of players for event payloads.
 *
 * <p>Same JSON shape as {@code RoomStateResponse.PlayerView}, built here so the
 * engine does not have to depend on the REST DTO package.
 */
public final class GameViews {

    private GameViews() {
    }

    public static Map<String, Object> player(Player p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.getId());
        view.put("name", p.getName());
        view.put("score", p.getScore());
        view.put("roundScore", p.getRoundScore());
        view.put("host", p.isHost());
        view.put("connected", p.isConnected());
        view.put("guessedCurrentRound", p.hasGuessedCurrentRound());
        return view;
    }

    /** Players in join order. */
    public static List<Map<String, Object>> players(Room room) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player p : room.getPlayers()) {
            out.add(player(p));
        }
        return out;
    }

    /** Players sorted highest score first. */
    public static List<Map<String, Object>> leaderboard(Room room) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player p : room.getGame().getLeaderboard()) {
            out.add(player(p));
        }
        return out;
    }

    /**
     * Builds a payload map from alternating keys and values. Unlike
     * {@code Map.of}, it accepts null values, which several payloads need
     * (for example {@code nextDrawerId} on the final turn).
     */
    public static Map<String, Object> payload(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("payload needs key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }
}
