package cc.minedsa.halochat;

import java.time.Duration;

/**
 * Constants shared by the chat plugin.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
public final class Constants {

    private Constants() {
    }

    /** Group of the custom resources owned by this plugin. */
    public static final String EXTENSION_GROUP = "chat.halo.run";

    /** Group of the public API used by the console and the standalone chat page. */
    public static final String PUBLIC_API_GROUP = "api.chat.halo.run";

    /** Group of the admin API. It is only reachable by super admins or assigned roles. */
    public static final String CONSOLE_API_GROUP = "console.api.chat.halo.run";

    public static final String API_VERSION = "v1alpha1";

    /** Fixed name of the channel that is always pinned on top and open to everyone. */
    public static final String PUBLIC_CHANNEL_NAME = "public";

    public static final String PUBLIC_CHANNEL_DISPLAY_NAME = "公共频道";

    /** Secret holding the HMAC key used to sign guest cookies. */
    public static final String SECRET_NAME = "halo-chat-signing-key";

    public static final String SECRET_KEY_GUEST_SIGNING = "guest-signing-key";

    public static final String GUEST_COOKIE = "halo-chat-guest";

    public static final Duration GUEST_COOKIE_TTL = Duration.ofDays(30);

    /** Label/annotation keys. */
    public static final String ANNO_COMMENT_NAME = "chat.halo.run/source-comment";
    public static final String ANNO_ON_BEHALF_OF = "chat.halo.run/on-behalf-of";
    public static final String ANNO_EDITED_BY = "chat.halo.run/edited-by";
    public static final String LABEL_CHANNEL = "chat.halo.run/channel";

    /** UI permissions exposed through role templates. */
    public static final String UI_PERMISSION_VIEW = "plugin:chat:view";
    public static final String UI_PERMISSION_MANAGE = "plugin:chat:manage";

    public static final String SETTING_NAME = "halo-chat-settings";
    public static final String CONFIG_MAP_NAME = "halo-chat-configmap";

    public static final int MAX_MESSAGE_LENGTH = 4000;
    public static final int MAX_FILES_PER_MESSAGE = 5;
}
