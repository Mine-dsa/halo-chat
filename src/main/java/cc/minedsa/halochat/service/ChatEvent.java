package cc.minedsa.halochat.service;

import java.time.Instant;

/**
 * One realtime event delivered over Server-Sent Events.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
public record ChatEvent(String type, String channel, Object payload, Instant at) {

    public static ChatEvent message(String type, String channel, Object message) {
        return new ChatEvent(type, channel, message, Instant.now());
    }

    public static ChatEvent channel(String type, String channel, Object payload) {
        return new ChatEvent(type, channel, payload, Instant.now());
    }
}
