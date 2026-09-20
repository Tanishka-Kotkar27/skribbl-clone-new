package com.skribbl.rest;

import com.skribbl.game.RoomRegistry;
import com.skribbl.ws.SessionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness endpoint. Deployment platforms poll this, and it is the first thing
 * to curl when something looks wrong in production.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final RoomRegistry roomRegistry;
    private final SessionRegistry sessionRegistry;

    public HealthController(RoomRegistry roomRegistry, SessionRegistry sessionRegistry) {
        this.roomRegistry = roomRegistry;
        this.sessionRegistry = sessionRegistry;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "activeRooms", roomRegistry.size(),
                "activeSockets", sessionRegistry.size(),
                "serverTime", System.currentTimeMillis());
    }
}
