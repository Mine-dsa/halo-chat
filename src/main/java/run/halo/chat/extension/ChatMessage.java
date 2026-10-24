package run.halo.chat.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A single chat message.
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "chat.halo.run", version = "v1alpha1", kind = "ChatMessage",
    plural = "chatmessages", singular = "chatmessage")
public class ChatMessage extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    public enum SenderType {
        USER, GUEST, SYSTEM
    }

    public enum ContentType {
        TEXT, HTML
    }

    public enum SourceType {
        BUILTIN, COMMENT, ADMIN
    }

    @Data
    @NoArgsConstructor
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String channel;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private SenderType senderType = SenderType.USER;

        /** Halo username for USER, guest id for GUEST, {@code system} for SYSTEM. */
        private String senderName;

        @Schema(maxLength = 60)
        private String senderDisplayName;

        private String senderAvatar;

        private ContentType contentType = ContentType.TEXT;

        @Schema(maxLength = 20000)
        private String content;

        private List<Attachment> attachments = new ArrayList<>();

        private SourceType source = SourceType.BUILTIN;

        /** Name of the mirrored Comment when {@link #source} is COMMENT. */
        private String sourceName;

        private Instant editedAt;

        private String editedBy;

        private Boolean deleted = false;

        private Instant deletedAt;
    }

    @Data
    @NoArgsConstructor
    public static class Attachment {
        private String fileRef;
        private String name;
        private Long size;
        private String mediaType;
        private Boolean expired;
    }
}
