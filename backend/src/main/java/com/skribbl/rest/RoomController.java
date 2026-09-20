package com.skribbl.rest;

import com.skribbl.rest.dto.CreateRoomRequest;
import com.skribbl.rest.dto.JoinRoomRequest;
import com.skribbl.rest.dto.JoinRoomResponse;
import com.skribbl.rest.dto.PlayerActionRequest;
import com.skribbl.rest.dto.RoomStateResponse;
import com.skribbl.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Room and lobby REST surface.
 *
 * <p>Why these actions are REST and not WebSocket: creating and joining happen
 * <em>before</em> a socket exists, they are request/response rather than
 * broadcast, and they benefit from ordinary HTTP status codes and Bean
 * Validation. Everything that happens once you are in a room — strokes, guesses,
 * timers — is push-shaped and lives on the WebSocket instead.
 *
 * <p>Starting a game is REST too: it is a one-off command with a clear
 * success/failure answer (403 not host, 409 not enough players), which maps
 * naturally onto HTTP status codes.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /** Creates a room with the given settings and seats the caller as host. */
    @PostMapping
    public ResponseEntity<JoinRoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        JoinRoomResponse response =
                roomService.createRoom(request.hostName(), request.settingsOrDefault());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Seats a player in an existing room. */
    @PostMapping("/{code}/join")
    public JoinRoomResponse joinRoom(@PathVariable String code,
                                     @Valid @RequestBody JoinRoomRequest request) {
        return roomService.joinRoom(code.toUpperCase(), request.playerName());
    }

    /**
     * Host starts the game. Returns 202: the game has started, and everything
     * that follows arrives over the WebSocket rather than in this response.
     */
    @PostMapping("/{code}/start")
    public ResponseEntity<RoomStateResponse> startGame(@PathVariable String code,
                                                       @Valid @RequestBody PlayerActionRequest request) {
        RoomStateResponse state = roomService.startGame(code.toUpperCase(), request.playerId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(state);
    }

    /** Player clicks Leave. */
    @PostMapping("/{code}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable String code,
                                          @Valid @RequestBody PlayerActionRequest request) {
        roomService.leaveRoom(code.toUpperCase(), request.playerId());
        return ResponseEntity.noContent().build();
    }

    /** Lobby/game state, used on page load and after a refresh. */
    @GetMapping("/{code}")
    public RoomStateResponse getRoom(@PathVariable String code) {
        return roomService.getRoomState(code.toUpperCase());
    }
}
