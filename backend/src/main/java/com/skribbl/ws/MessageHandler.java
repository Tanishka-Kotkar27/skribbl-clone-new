package com.skribbl.ws;

import com.skribbl.game.DrawingService;
import com.skribbl.game.GameActionException;
import com.skribbl.game.GameEngine;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.RoomRegistry;
import com.skribbl.game.Stroke;
import com.skribbl.rest.dto.RoomStateResponse;
import com.skribbl.ws.dto.ClientMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single inbound entry point for STOMP frames — the {@code MessageHandler}
 * class the brief asks for.
 *
 * <p>Its job is deliberately narrow: <em>parse, authorise, delegate, broadcast</em>.
 * No game rules live here. A handler resolves the room, checks the sender is
 * allowed to do the thing (only the drawer may clear the canvas, only the host
 * may start), calls into {@link com.skribbl.game.Game} through a service, and
 * hands the result to {@link RoomBroadcaster}. Keeping rules out of this class
 * is what lets the game logic be tested without a socket.
 *
 * <p><strong>Destinations, and which phase adds them:</strong>
 * <table border="1">
 *   <caption>Inbound destination map</caption>
 *   <tr><th>Destination</th><th>Payload</th><th>Phase</th></tr>
 *   <tr><td>{@code /app/room/{code}/join}</td><td>{@code Join}</td><td>1 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/ping}</td><td>{@code Ping}</td><td>1 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/word-chosen}</td><td>{@code WordChosen}</td><td>3 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/draw-start}</td><td>{@code DrawStart}</td><td>6 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/draw-move}</td><td>{@code DrawMove}</td><td>6 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/draw-end}</td><td>{@code DrawEnd}</td><td>6 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/undo}</td><td>&mdash;</td><td>6 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/clear}</td><td>&mdash;</td><td>6 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/chat}</td><td>{@code Chat}</td><td>7 — done</td></tr>
 *   <tr><td>{@code /app/room/{code}/guess}</td><td>{@code Chat}</td><td>7 — done (same handler)</td></tr>
 * </table>
 *
 * <p>Outbound events all travel as a {@code ServerEvent} envelope; see
 * {@link EventType} for the full list.
 */
@Controller
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final RoomRegistry roomRegistry;
    private final RoomBroadcaster broadcaster;
    private final SessionRegistry sessionRegistry;
    private final GameEngine gameEngine;
    private final DrawingService drawingService;

    public MessageHandler(RoomRegistry roomRegistry,
                          RoomBroadcaster broadcaster,
                          SessionRegistry sessionRegistry,
                          GameEngine gameEngine,
                          DrawingService drawingService) {
        this.roomRegistry = roomRegistry;
        this.broadcaster = broadcaster;
        this.sessionRegistry = sessionRegistry;
        this.gameEngine = gameEngine;
        this.drawingService = drawingService;
    }

    /**
     * Binds this socket to a player who has already joined over REST, marks them
     * connected, and replies with the full room state so the client can render
     * the lobby without a second HTTP call.
     */
    @MessageMapping("/room/{code}/join")
    public void handleJoin(@DestinationVariable String code,
                           @Payload ClientMessages.Join message,
                           SimpMessageHeaderAccessor headers) {
        String sessionId = headers.getSessionId();
        Optional<Room> maybeRoom = roomRegistry.findByCode(code);

        if (maybeRoom.isEmpty()) {
            broadcaster.sendToSession(sessionId, EventType.ERROR,
                    Map.of("message", "Room " + code + " no longer exists"));
            return;
        }

        Room room = maybeRoom.get();
        room.lock();
        try {
            Optional<Player> maybePlayer = room.getPlayer(message.playerId());
            if (maybePlayer.isEmpty()) {
                broadcaster.sendToSession(sessionId, EventType.ERROR,
                        Map.of("message", "Join the room over REST before connecting"));
                return;
            }

            Player player = maybePlayer.get();
            player.setConnected(true);
            player.setSessionId(sessionId);
            sessionRegistry.bind(sessionId, room.getCode(), player.getId(), player.getName());

            // The joiner gets the whole picture; everyone else just gets the delta.
            broadcaster.sendToSession(sessionId, EventType.ROOM_STATE,
                    RoomStateResponse.from(room));
            broadcaster.broadcast(room.getCode(), EventType.PLAYER_JOINED,
                    Map.of(
                            "player", RoomStateResponse.PlayerView.from(player),
                            "players", RoomStateResponse.from(room).players()));

            // Cancels a pending removal if this is a reconnect, and re-sends the
            // drawer their word choices or secret word if they refreshed mid-turn.
            gameEngine.onPlayerConnected(room, player);
            // Someone arriving mid-turn sees the drawing so far, not a blank canvas.
            drawingService.replayTo(room, player);

            log.debug("Player {} bound socket {} to room {}",
                    player.getName(), sessionId, room.getCode());
        } finally {
            room.unlock();
        }
    }

    /**
     * The drawer picked a word.
     *
     * <p>Who is asking comes from the socket's session binding, <em>not</em> from
     * the message body. Trusting a player id sent in the payload would let any
     * guesser pick the word by claiming to be the drawer.
     */
    @MessageMapping("/room/{code}/word-chosen")
    public void handleWordChosen(@DestinationVariable String code,
                                 @Payload ClientMessages.WordChosen message,
                                 SimpMessageHeaderAccessor headers) {
        String sessionId = headers.getSessionId();
        Optional<SessionRegistry.Binding> binding = sessionRegistry.lookup(sessionId);
        Optional<Room> room = roomRegistry.findByCode(code);
        if (binding.isEmpty() || room.isEmpty()
                || !binding.get().roomCode().equals(room.get().getCode())) {
            broadcaster.sendToSession(sessionId, EventType.ERROR,
                    Map.of("message", "You are not in this room"));
            return;
        }
        try {
            gameEngine.chooseWord(room.get(), binding.get().playerId(), message.word());
        } catch (GameActionException e) {
            broadcaster.sendToSession(sessionId, EventType.ERROR,
                    Map.of("message", e.getMessage(), "reason", e.getReason().name()));
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Stroke messages. The drawer is identified from the socket, never from the
     * payload, and {@link DrawingService} checks it is actually their turn.
     * Rejected stroke messages are dropped without a reply: answering every
     * stray pointermove with an error would flood the connection.
     */
    @MessageMapping("/room/{code}/draw-start")
    public void handleDrawStart(@DestinationVariable String code,
                                @Payload ClientMessages.DrawStart message,
                                SimpMessageHeaderAccessor headers) {
        withPlayer(code, headers, (room, playerId) -> drawingService.startStroke(
                room, playerId, message.strokeId(), message.x(), message.y(),
                message.color(), message.size(), message.eraser()));
    }

    @MessageMapping("/room/{code}/draw-move")
    public void handleDrawMove(@DestinationVariable String code,
                               @Payload ClientMessages.DrawMove message,
                               SimpMessageHeaderAccessor headers) {
        if (message.points() == null) {
            return;
        }
        List<Stroke.Point> points = new ArrayList<>(message.points().size());
        for (ClientMessages.Point p : message.points()) {
            if (p != null) {
                points.add(new Stroke.Point(p.x(), p.y()));
            }
        }
        withPlayer(code, headers, (room, playerId) ->
                drawingService.addPoints(room, playerId, message.strokeId(), points));
    }

    @MessageMapping("/room/{code}/draw-end")
    public void handleDrawEnd(@DestinationVariable String code,
                              @Payload ClientMessages.DrawEnd message,
                              SimpMessageHeaderAccessor headers) {
        withPlayer(code, headers, (room, playerId) ->
                drawingService.endStroke(room, playerId, message.strokeId()));
    }

    @MessageMapping("/room/{code}/undo")
    public void handleUndo(@DestinationVariable String code, SimpMessageHeaderAccessor headers) {
        withPlayer(code, headers, drawingService::undo);
    }

    @MessageMapping("/room/{code}/clear")
    public void handleClear(@DestinationVariable String code, SimpMessageHeaderAccessor headers) {
        withPlayer(code, headers, drawingService::clear);
    }

    // --------------------------------------------------------- chat & guesses

    /**
     * Chat and guesses share one input, as in skribbl.io; the engine decides
     * whether the text is a correct guess, a wrong one, or plain chat. Both
     * destinations are accepted so the event names in the brief work too.
     */
    @MessageMapping({"/room/{code}/chat", "/room/{code}/guess"})
    public void handleChat(@DestinationVariable String code,
                           @Payload ClientMessages.Chat message,
                           SimpMessageHeaderAccessor headers) {
        withPlayer(code, headers, (room, playerId) ->
                gameEngine.handleChat(room, playerId, message.text()));
    }

    /** Resolves the sender from their socket and runs the action if they belong to this room. */
    private void withPlayer(String code, SimpMessageHeaderAccessor headers,
                            java.util.function.BiConsumer<Room, String> action) {
        Optional<SessionRegistry.Binding> binding = sessionRegistry.lookup(headers.getSessionId());
        Optional<Room> room = roomRegistry.findByCode(code);
        if (binding.isEmpty() || room.isEmpty()
                || !binding.get().roomCode().equals(room.get().getCode())) {
            return;
        }
        action.accept(room.get(), binding.get().playerId());
    }

    /**
     * Liveness probe. The Phase 1 smoke-test page sends this and waits for the
     * matching {@code pong}, which proves the whole STOMP path works before any
     * game code exists to confuse the diagnosis.
     */
    @MessageMapping("/room/{code}/ping")
    public void handlePing(@DestinationVariable String code,
                           @Payload ClientMessages.Ping message,
                           SimpMessageHeaderAccessor headers) {
        broadcaster.sendToSession(headers.getSessionId(), EventType.PONG,
                Map.of(
                        "echo", message.text() == null ? "" : message.text(),
                        "room", code,
                        "serverTime", System.currentTimeMillis()));
    }
}
