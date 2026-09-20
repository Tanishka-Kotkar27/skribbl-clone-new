package com.skribbl.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for player commands over REST (start, leave).
 *
 * <p>The player id is the UUID handed out at join time. There are no accounts in
 * this game, so possession of that id is what identifies you; it is never shown
 * to other players (they see names only).
 */
public record PlayerActionRequest(
        @NotBlank(message = "playerId is required")
        String playerId
) {
}
