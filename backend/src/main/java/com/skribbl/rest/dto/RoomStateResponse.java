package com.skribbl.rest.dto;

import com.skribbl.game.Game;
import com.skribbl.game.GamePhase;
import com.skribbl.game.GameSettings;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.RoomStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a client needs to render the lobby or game screen.
 *
 * <p>Critically, this carries {@code maskedWord} and never {@code currentWord}.
 * The secret word only ever leaves the server on the drawer's private queue.
 * Putting the plain word in the shared room state — even "just for debugging" —
 * is the mistake that silently breaks the entire game, because anyone with
 * devtools open can read it.
 */
public record RoomStateResponse(
        String code,
        String hostId,
        RoomStatus status,
        GamePhase phase,
        GameSettings settings,
        List<PlayerView> players,
        int currentRound,
        int totalRounds,
        String drawerId,
        String maskedWord,
        int remainingSeconds
) {

    /** A player as other players are allowed to see them. */
    public record PlayerView(
            String id,
            String name,
            int score,
            int roundScore,
            boolean host,
            boolean connected,
            boolean guessedCurrentRound
    ) {
        public static PlayerView from(Player player) {
            return new PlayerView(
                    player.getId(),
                    player.getName(),
                    player.getScore(),
                    player.getRoundScore(),
                    player.isHost(),
                    player.isConnected(),
                    player.hasGuessedCurrentRound());
        }
    }

    /** Snapshot a room. Caller should hold the room lock. */
    public static RoomStateResponse from(Room room) {
        Game game = room.getGame();
        List<PlayerView> views = new ArrayList<>();
        for (Player p : room.getPlayers()) {
            views.add(PlayerView.from(p));
        }
        return new RoomStateResponse(
                room.getCode(),
                room.getHostId(),
                room.getStatus(),
                game.getPhase(),
                room.getSettings(),
                views,
                game.getCurrentRound(),
                room.getSettings().getRounds(),
                game.getDrawerId(),
                game.getMaskedWord(),
                game.getRemainingSeconds());
    }
}
