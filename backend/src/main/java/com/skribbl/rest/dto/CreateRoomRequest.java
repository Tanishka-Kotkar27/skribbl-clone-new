package com.skribbl.rest.dto;

import com.skribbl.game.GameSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/rooms}. The nested settings are validated by the
 * ranges declared on {@link GameSettings}, so an out-of-range value is rejected
 * with HTTP 400 before any room object is created.
 */
public record CreateRoomRequest(
        @NotBlank(message = "hostName is required")
        @Size(min = 1, max = 20, message = "hostName must be 1-20 characters")
        String hostName,

        @Valid
        GameSettings settings
) {
    /** Settings are optional; defaults apply when the client omits them. */
    public GameSettings settingsOrDefault() {
        return settings == null ? new GameSettings() : settings;
    }
}
