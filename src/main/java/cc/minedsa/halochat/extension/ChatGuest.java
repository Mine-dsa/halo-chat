package cc.minedsa.halochat.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Server side guest session. The browser only knows the generated guest id and
 * the HMAC signature, the nickname is always resolved from this resource.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "chat.halo.run", version = "v1alpha1", kind = "ChatGuest",
    plural = "chatguests", singular = "chatguest")
public class ChatGuest extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @NoArgsConstructor
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 60)
        private String nickname;

        /** Hashed client address, used for abuse detection only. */
        private String ipHash;

        private String userAgent;

        private Instant createdAt;

        private Instant lastSeenAt;

        private Long messageCount = 0L;
    }
}
