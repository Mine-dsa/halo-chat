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
 * A chat channel. The channel with name {@code public} is the built-in public
 * channel, all other channels are groups or topics created by administrators.
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "chat.halo.run", version = "v1alpha1", kind = "ChatChannel",
    plural = "chatchannels", singular = "chatchannel")
public class ChatChannel extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    public enum Type {
        PUBLIC, GROUP, TOPIC
    }

    public enum AccessPolicy {
        /** Everyone, including visitors without a Halo account. */
        EVERYONE,
        /** Any signed-in Halo user. */
        AUTHENTICATED,
        /** Only users listed in {@code spec.members}. */
        MEMBERS
    }

    public enum ComposerMode {
        INHERIT, BUILTIN, HALO_COMMENT
    }

    @Data
    @NoArgsConstructor
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Type type = Type.GROUP;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 60)
        private String displayName;

        @Schema(maxLength = 500)
        private String description;

        private String avatar;

        /** Username of the creator. */
        private String owner;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private AccessPolicy visibility = AccessPolicy.AUTHENTICATED;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private AccessPolicy postPolicy = AccessPolicy.AUTHENTICATED;

        /** Usernames allowed to read/post when the policy is MEMBERS. */
        private List<String> members = new ArrayList<>();

        /** Whether the built-in public channel can be dissolved. */
        private Boolean protectedChannel = false;

        private ComposerMode composerMode = ComposerMode.INHERIT;

        private Announcement announcement;

        /** Last time a message was created in this channel, maintained by the plugin. */
        private Instant lastMessageAt;
    }

    @Data
    @NoArgsConstructor
    public static class Announcement {
        private String content;
        private Instant updatedAt;
        private String updatedBy;
    }
}
