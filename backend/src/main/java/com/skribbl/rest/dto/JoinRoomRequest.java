package com.skribbl.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/rooms/{code}/join}. */
public record JoinRoomRequest(
        @NotBlank(message = "playerName is required")
        @Size(min = 1, max = 20, message = "playerName must be 1-20 characters")
        String playerName
) {
}
