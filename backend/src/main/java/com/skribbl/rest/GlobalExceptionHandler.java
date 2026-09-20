package com.skribbl.rest;

import com.skribbl.game.GameActionException;
import com.skribbl.rest.dto.ApiError;
import com.skribbl.service.RoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates exceptions into the single {@link ApiError} shape.
 *
 * <p>Validation failures are the interesting case: the brief requires settings
 * ranges to be enforced, and this turns a violated {@code @Min}/{@code @Max}
 * into a 400 whose body names the offending field, so the React form can show
 * the error next to the right slider instead of a generic "something failed".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidationFailure(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }
        ApiError body = new ApiError(
                "validation_failed",
                "One or more settings are outside the allowed range",
                fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(RoomService.RoomNotFoundException.class)
    public ResponseEntity<ApiError> onRoomNotFound(RoomService.RoomNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("room_not_found", ex.getMessage()));
    }

    @ExceptionHandler(RoomService.RoomUnavailableException.class)
    public ResponseEntity<ApiError> onRoomUnavailable(RoomService.RoomUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("room_unavailable", ex.getMessage()));
    }

    /**
     * Rule violations from the game engine. Permission problems are 403,
     * "not right now" problems are 409, a bad value is 400.
     */
    @ExceptionHandler(GameActionException.class)
    public ResponseEntity<ApiError> onGameAction(GameActionException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case NOT_HOST, NOT_DRAWER -> HttpStatus.FORBIDDEN;
            case NOT_ENOUGH_PLAYERS, ALREADY_STARTED, WRONG_PHASE -> HttpStatus.CONFLICT;
            case INVALID_WORD -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiError.of(ex.getReason().name().toLowerCase(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("bad_request", ex.getMessage()));
    }
}
