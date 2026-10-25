package cc.minedsa.halochat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import cc.minedsa.halochat.Constants;
import cc.minedsa.halochat.config.SettingsService;
import cc.minedsa.halochat.extension.ChatChannel;
import cc.minedsa.halochat.support.ChatException;

/**
 * Channel CRUD and access control.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelService {

    private final ReactiveExtensionClient client;
    private final SettingsService settingsService;

    /** Creates the built-in public channel when it does not exist yet. */
    public Mono<Void> ensurePublicChannel() {
        return client.fetch(ChatChannel.class, Constants.PUBLIC_CHANNEL_NAME)
            .flatMap(channel -> {
                var spec = channel.getSpec();
                var changed = false;
                if (spec.getType() != ChatChannel.Type.PUBLIC) {
                    spec.setType(ChatChannel.Type.PUBLIC);
                    changed = true;
                }
                if (!Boolean.TRUE.equals(spec.getProtectedChannel())) {
                    spec.setProtectedChannel(true);
                    changed = true;
                }
                if (spec.getVisibility() != ChatChannel.AccessPolicy.EVERYONE) {
                    spec.setVisibility(ChatChannel.AccessPolicy.EVERYONE);
                    changed = true;
                }
                if (spec.getPostPolicy() != ChatChannel.AccessPolicy.EVERYONE) {
                    spec.setPostPolicy(ChatChannel.AccessPolicy.EVERYONE);
                    changed = true;
                }
                return changed ? client.update(channel).then() : Mono.empty();
            })
            .then()
            .switchIfEmpty(Mono.defer(() -> createPublicChannel().then()));
    }

    private Mono<ChatChannel> createPublicChannel() {
        var channel = new ChatChannel();
        var metadata = new Metadata();
        metadata.setName(Constants.PUBLIC_CHANNEL_NAME);
        channel.setMetadata(metadata);
        var spec = new ChatChannel.Spec();
        spec.setType(ChatChannel.Type.PUBLIC);
        spec.setDisplayName(Constants.PUBLIC_CHANNEL_DISPLAY_NAME);
        spec.setDescription("所有人都可以加入的公共聊天频道");
        spec.setProtectedChannel(true);
        spec.setVisibility(ChatChannel.AccessPolicy.EVERYONE);
        spec.setPostPolicy(ChatChannel.AccessPolicy.EVERYONE);
        spec.setOwner("system");
        channel.setSpec(spec);
        return client.create(channel)
            .onErrorResume(error -> client.fetch(ChatChannel.class, Constants.PUBLIC_CHANNEL_NAME));
    }

    public Mono<ChatChannel> requireChannel(String name) {
        if (!StringUtils.hasText(name)) {
            return Mono.error(ChatException.badRequest("频道名称不能为空"));
        }
        return client.fetch(ChatChannel.class, name)
            .switchIfEmpty(Mono.error(ChatException.notFound("频道不存在")));
    }

    public Flux<ChatChannel> listAll() {
        return client.listAll(ChatChannel.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.desc("metadata.creationTimestamp")))
            .filter(channel -> channel.getMetadata().getDeletionTimestamp() == null);
    }

    /** Channels visible to the given identity, public channel first. */
    public Flux<ChatChannel> listVisible(Identity identity) {
        return listAll()
            .filter(channel -> canRead(identity, channel))
            .sort(channelComparator());
    }

    public Mono<ChatChannel> requireReadable(Identity identity, String name) {
        return requireChannel(name)
            .filter(channel -> canRead(identity, channel))
            .switchIfEmpty(Mono.error(ChatException.forbidden("没有权限查看该频道")));
    }

    public Mono<ChatChannel> requirePostable(Identity identity, String name) {
        return requireChannel(name)
            .flatMap(channel -> canPost(identity, channel).flatMap(allowed ->
                allowed ? Mono.just(channel) : Mono.error(ChatException.forbidden("没有权限在该频道发言"))));
    }

    public boolean canRead(Identity identity, ChatChannel channel) {
        if (channel == null || identity == null) {
            return false;
        }
        if (identity.canManage()) {
            return true;
        }
        if (channel.getSpec().getType() == ChatChannel.Type.PUBLIC) {
            return true;
        }
        return switch (channel.getSpec().getVisibility()) {
            case EVERYONE -> true;
            case AUTHENTICATED -> identity.isUser();
            case MEMBERS -> identity.isUser() && isMember(identity, channel);
        };
    }

    public Mono<Boolean> canPost(Identity identity, ChatChannel channel) {
        if (identity == null || channel == null) {
            return Mono.just(false);
        }
        if (identity.canManage()) {
            return Mono.just(true);
        }
        var spec = channel.getSpec();
        if (identity.isAnonymous()) {
            return Mono.just(false);
        }
        if (identity.isGuest()) {
            if (spec.getType() != ChatChannel.Type.PUBLIC
                && spec.getPostPolicy() != ChatChannel.AccessPolicy.EVERYONE) {
                return Mono.just(false);
            }
            return settingsService.general()
                .map(general -> Boolean.TRUE.equals(general.getGuestEnabled()));
        }
        return Mono.just(switch (spec.getPostPolicy()) {
            case EVERYONE, AUTHENTICATED -> true;
            case MEMBERS -> isMember(identity, channel);
        });
    }

    public boolean isMember(Identity identity, ChatChannel channel) {
        var members = channel.getSpec().getMembers();
        return identity != null && identity.name() != null && members != null
            && members.contains(identity.name());
    }

    public Mono<ChatChannel> create(ChannelCreateCommand command) {
        var channel = new ChatChannel();
        var metadata = new Metadata();
        metadata.setGenerateName("chat-channel-");
        channel.setMetadata(metadata);
        var spec = new ChatChannel.Spec();
        spec.setType(command.type() == ChatChannel.Type.PUBLIC
            ? ChatChannel.Type.GROUP : command.type());
        spec.setVisibility(command.visibility());
        spec.setPostPolicy(command.postPolicy());
        spec.setComposerMode(command.composerMode());
        spec.setOwner(command.owner());
        channel.setSpec(spec);
        return validateAndApplySpec(channel, command, true)
            .then(client.create(channel));
    }

    public Mono<ChatChannel> update(String name, ChannelCreateCommand command) {
        return requireChannel(name).flatMap(channel -> {
            if (channel.getSpec().getType() == ChatChannel.Type.PUBLIC
                && Boolean.TRUE.equals(channel.getSpec().getProtectedChannel())) {
                // permissions of the public channel are intentionally fixed
                if (command.visibility() != null
                    && command.visibility() != ChatChannel.AccessPolicy.EVERYONE) {
                    return Mono.error(ChatException.badRequest("公共频道必须对所有访客可见"));
                }
                if (command.postPolicy() != null
                    && command.postPolicy() != ChatChannel.AccessPolicy.EVERYONE) {
                    return Mono.error(ChatException.badRequest("公共频道必须允许所有人发言"));
                }
            }
            return validateAndApplySpec(channel, command, false).then(client.update(channel));
        });
    }

    public Mono<ChatChannel> updateAnnouncement(String name, String content, String updatedBy) {
        return requireChannel(name).flatMap(channel -> {
            var announcement = new ChatChannel.Announcement();
            if (StringUtils.hasText(content)) {
                announcement.setContent(content.strip());
                announcement.setUpdatedAt(Instant.now());
                announcement.setUpdatedBy(updatedBy);
                channel.getSpec().setAnnouncement(announcement);
            } else {
                channel.getSpec().setAnnouncement(null);
            }
            return client.update(channel);
        });
    }

    public Mono<ChatChannel> requireChannelForAdmin(String name) {
        return requireChannel(name);
    }

    public Mono<Void> touchLastMessage(String channelName, Instant at) {
        return client.fetch(ChatChannel.class, channelName)
            .flatMap(channel -> {
                var spec = channel.getSpec();
                if (spec.getLastMessageAt() != null && spec.getLastMessageAt().isAfter(at)) {
                    return Mono.empty();
                }
                spec.setLastMessageAt(at);
                return client.update(channel).then();
            })
            .onErrorResume(error -> {
                log.debug("Failed to update last message time of channel {}", channelName, error);
                return Mono.empty();
            });
    }

    public Mono<List<ChatChannel>> sortedListVisible(Identity identity) {
        return listVisible(identity).collectList();
    }

    public Comparator<ChatChannel> channelComparator() {
        return Comparator
            .comparing((ChatChannel channel) ->
                channel.getSpec().getType() != ChatChannel.Type.PUBLIC)
            .thenComparing(channel -> {
                var last = channel.getSpec().getLastMessageAt();
                return last == null ? Instant.EPOCH : last;
            }, Comparator.reverseOrder())
            .thenComparing(channel -> channel.getMetadata().getCreationTimestamp(),
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private Mono<Void> validateAndApplySpec(ChatChannel channel, ChannelCreateCommand command,
                                            boolean creating) {
        var spec = channel.getSpec();
        var displayName = command.displayName() == null ? null : command.displayName().strip();
        if (creating && !StringUtils.hasText(displayName)) {
            return Mono.error(ChatException.badRequest("频道名称不能为空"));
        }
        if (displayName != null) {
            if (displayName.length() > 60) {
                return Mono.error(ChatException.badRequest("频道名称不能超过 60 个字符"));
            }
            spec.setDisplayName(displayName);
        }
        if (command.description() != null) {
            if (command.description().length() > 500) {
                return Mono.error(ChatException.badRequest("频道简介不能超过 500 个字符"));
            }
            spec.setDescription(command.description().strip());
        }
        if (command.avatar() != null) {
            spec.setAvatar(command.avatar());
        }
        if (command.type() != null && creating) {
            spec.setType(command.type());
        }
        if (command.visibility() != null) {
            spec.setVisibility(command.visibility());
        }
        if (command.postPolicy() != null) {
            spec.setPostPolicy(command.postPolicy());
        }
        if (command.composerMode() != null) {
            spec.setComposerMode(command.composerMode());
        }
        if (command.members() != null) {
            var members = command.members().stream()
                .filter(StringUtils::hasText)
                .map(String::strip)
                .distinct()
                .toList();
            return validateMembers(members).doOnNext(spec::setMembers).then();
        }
        return Mono.empty();
    }

    private Mono<List<String>> validateMembers(List<String> members) {
        if (members.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }
        return Flux.fromIterable(members)
            .flatMap(username -> client.fetch(User.class, username)
                .map(user -> username)
                .defaultIfEmpty(""))
            .filter(StringUtils::hasText)
            .collectList();
    }

    /**
     * Command object used by admin create/update endpoints.
     */
    public record ChannelCreateCommand(
        ChatChannel.Type type,
        String displayName,
        String description,
        String avatar,
        ChatChannel.AccessPolicy visibility,
        ChatChannel.AccessPolicy postPolicy,
        List<String> members,
        ChatChannel.ComposerMode composerMode,
        String owner) {
    }

    public String publicChannelName() {
        return Constants.PUBLIC_CHANNEL_NAME;
    }

    public boolean isPublicChannel(ChatChannel channel) {
        return channel != null && Objects.equals(Constants.PUBLIC_CHANNEL_NAME,
            channel.getMetadata().getName());
    }
}
