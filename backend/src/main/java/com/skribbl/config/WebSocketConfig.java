package com.skribbl.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP-over-WebSocket wiring.
 *
 * <p>Destination scheme used throughout the app:
 * <ul>
 *   <li>{@code /app/**} &mdash; inbound, client to server. Handled by
 *       {@code @MessageMapping} methods on {@link com.skribbl.ws.MessageHandler}.</li>
 *   <li>{@code /topic/room/{code}} &mdash; outbound broadcast to everyone in a room
 *       (player list changes, strokes, chat, timer ticks, round transitions).</li>
 *   <li>{@code /user/queue/**} &mdash; outbound to a single player. This is how the
 *       drawer receives their word choices and the secret word without leaking
 *       them to the guessers.</li>
 * </ul>
 *
 * <p>The simple in-memory broker is deliberate: game state is per-room and lives
 * in one JVM, so an external broker (RabbitMQ / ActiveMQ) would add operational
 * cost for no benefit at this scale. Horizontal scaling would require either a
 * relay broker or sticky sessions plus a shared room registry; that trade-off is
 * documented in docs/ARCHITECTURE.md.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties appProperties;

    public WebSocketConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket endpoint.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(appProperties.getCorsOriginArray());

        // Same endpoint with SockJS fallback, for networks that block raw
        // WebSocket upgrades (some corporate proxies still do).
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(appProperties.getCorsOriginArray())
                .withSockJS();

        // Handle each client's messages in the order they were sent. Spring
        // otherwise spreads inbound frames across a thread pool, so two
        // draw-move batches from one drawer can be processed out of order — the
        // line would zig-zag — or draw-end can overtake the last draw-move.
        registry.setPreserveReceiveOrder(true);
    }

    /**
     * Room for a full canvas replay. A late joiner is sent every stroke of the
     * current drawing in one message; with the per-turn cap in
     * {@code DrawingService} that is a few hundred kilobytes at most, which is
     * above Spring's defaults.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(128 * 1024);         // inbound frame
        registration.setSendBufferSizeLimit(1024 * 1024);     // outbound buffer per session
        registration.setSendTimeLimit(15_000);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        // The same guarantee outbound: each client receives messages in the
        // order they were published, so strokes render in drawing order.
        registry.setPreservePublishOrder(true);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
