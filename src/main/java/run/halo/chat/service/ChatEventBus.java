package run.halo.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory fan-out of chat events for SSE subscribers.
 *
 * <p>This is intentionally single-node. Halo deployments running multiple
 * instances would need a shared bus for cross-instance realtime delivery.</p>
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Slf4j
@Component
public class ChatEventBus {

    private final Sinks.Many<ChatEvent> sink = Sinks.many().multicast().directBestEffort();

    public void emit(ChatEvent event) {
        var result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("No SSE subscriber for chat event {}", event.type());
        }
    }

    public void emitMessage(String type, String channel, Object message) {
        emit(ChatEvent.message(type, channel, message));
    }

    public void emitChannel(String type, String channel) {
        emit(ChatEvent.channel(type, channel, null));
    }

    public Flux<ChatEvent> subscribe(String channel) {
        var flux = sink.asFlux();
        if (channel == null || channel.isBlank()) {
            return flux;
        }
        return flux.filter(event -> channel.equals(event.channel()));
    }
}
