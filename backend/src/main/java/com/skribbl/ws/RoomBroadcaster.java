package com.skribbl.ws;

import com.skribbl.ws.dto.ServerEvent;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The single exit point for anything the server pushes over WebSocket.
 *
 * <p>Funnelling every outbound message through one class keeps destination
 * naming consistent and gives one obvious place to add logging or metrics. Two
 * delivery modes matter:
 *
 * <ul>
 *   <li><strong>Broadcast</strong> to {@code /topic/room/{code}} — everything the
 *       whole room may see.</li>
 *   <li><strong>Direct</strong> to one socket via {@code /user/queue/private} — the
 *       drawer's word choices and the confirmed word. Sending those to the room
 *       topic would hand the answer to every guesser, so this distinction is a
 *       correctness requirement, not a nicety.</li>
 * </ul>
 */
@Component
public class RoomBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public RoomBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public static String roomTopic(String roomCode) {
        return "/topic/room/" + roomCode;
    }

    /** Pushes an event to everyone subscribed to the room. */
    public void broadcast(String roomCode, String type, Object payload) {
        messagingTemplate.convertAndSend(roomTopic(roomCode), ServerEvent.of(type, payload));
    }

    /**
     * Pushes an event to a single socket.
     *
     * <p>The client subscribes to {@code /user/queue/private}; Spring rewrites
     * that per session, so the session id is used as the "user" here.
     */
    public void sendToSession(String sessionId, String type, Object payload) {
        if (sessionId == null) {
            return;
        }
        SimpMessageHeaderAccessor accessor =
                SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/private",
                ServerEvent.of(type, payload),
                accessor.getMessageHeaders());
    }
}
