package com.skribbl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code app.*} block of application.yml.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Origins allowed to call the REST API and open a WebSocket. */
    private String corsOrigins = "http://localhost:5173";

    /** How long an empty room lingers in memory before eviction. */
    private long roomTtlSeconds = 900L;

    /** Public base URL of the frontend, used to build shareable join links. */
    private String frontendUrl = "http://localhost:5173";

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public long getRoomTtlSeconds() {
        return roomTtlSeconds;
    }

    public void setRoomTtlSeconds(long roomTtlSeconds) {
        this.roomTtlSeconds = roomTtlSeconds;
    }

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    /** Convenience: the configured origins as an array, trimmed. */
    public String[] getCorsOriginArray() {
        String[] parts = corsOrigins.split(",");
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].trim();
        }
        return out;
    }
}
