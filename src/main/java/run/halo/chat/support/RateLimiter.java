package run.halo.chat.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny in-memory sliding window rate limiter. Good enough for a single Halo
 * instance; the plugin is documented as single-node for realtime features.
 *
 * @author halo-chat
 * @since 1.0.0
 */
public class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @return true when the call is allowed.
     */
    public boolean tryAcquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        var window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(now);
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> {
                var queue = entry.getValue();
                synchronized (queue) {
                    return queue.isEmpty()
                        || now - queue.peekLast() > WINDOW_MILLIS;
                }
            });
        }
        return true;
    }
}
