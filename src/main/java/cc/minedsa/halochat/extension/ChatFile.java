package cc.minedsa.halochat.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A file uploaded to a chat channel. The bytes are stored on the plugin data
 * directory, not as a Halo attachment, so that private channels keep their
 * content private and the plugin can enforce its own quotas and expiry.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "chat.halo.run", version = "v1alpha1", kind = "ChatFile",
    plural = "chatfiles", singular = "chatfile")
public class ChatFile extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    public enum UploaderType {
        USER, GUEST
    }

    @Data
    @NoArgsConstructor
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String channel;

        /** Name of the message that carries this file. */
        private String messageName;

        private UploaderType uploaderType = UploaderType.USER;

        private String uploaderName;

        private String uploaderDisplayName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
        private String filename;

        private String mediaType;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Long size;

        /** Relative path below the plugin data directory, never user supplied. */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String storageKey;

        /** SHA-256 of the file content, used for integrity checks and de-duplication. */
        private String sha256;

        /** Absolute expiry time; null means the file never expires. */
        private Instant expiresAt;

        private Instant deletedAt;
    }
}
