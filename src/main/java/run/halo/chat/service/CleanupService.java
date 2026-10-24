package run.halo.chat.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic maintenance: expired files, orphan temp files and inactive guest
 * sessions.
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupService {

    private final FileService fileService;
    private final GuestService guestService;

    @Scheduled(initialDelay = 180_000L, fixedDelay = 600_000L)
    public void cleanup() {
        fileService.cleanupExpired()
            .doOnNext(count -> {
                if (count > 0) {
                    log.info("Removed {} expired chat files", count);
                }
            })
            .then(fileService.cleanupTemp())
            .then(guestService.cleanupInactive(Duration.ofDays(90)))
            .subscribe(ignored -> {
                }, error -> log.warn("Chat cleanup failed", error));
    }
}
