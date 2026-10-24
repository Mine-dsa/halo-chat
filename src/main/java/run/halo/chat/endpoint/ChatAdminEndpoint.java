package run.halo.chat.endpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.chat.Constants;
import run.halo.chat.config.ChatSettings;
import run.halo.chat.config.SettingsService;
import run.halo.chat.extension.ChatChannel;
import run.halo.chat.extension.ChatFile;
import run.halo.chat.extension.ChatMessage;
import run.halo.chat.service.ChannelService;
import run.halo.chat.service.ChatEventBus;
import run.halo.chat.service.FileService;
import run.halo.chat.service.GuestService;
import run.halo.chat.service.Identity;
import run.halo.chat.service.IdentityService;
import run.halo.chat.service.MessageService;
import run.halo.chat.support.ChatException;
import run.halo.chat.support.Responses;

/**
 * Administration API. Access is controlled by Halo RBAC (super admin by
 * default, or any role created from the plugin's manage role template).
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAdminEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final IdentityService identityService;
    private final ChannelService channelService;
    private final MessageService messageService;
    private final FileService fileService;
    private final GuestService guestService;
    private final ChatEventBus eventBus;
    private final SettingsService settingsService;

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion(Constants.CONSOLE_API_GROUP, Constants.API_VERSION);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/overview", request -> Responses.guarded(() -> overview(request)))
            .GET("/settings", request -> Responses.guarded(() -> settings()))
            .GET("/channels", request -> Responses.guarded(() -> channels()))
            .POST("/channels", request -> Responses.guarded(() -> createChannel(request)))
            .PATCH("/channels/{name}", request -> Responses.guarded(() -> updateChannel(request)))
            .DELETE("/channels/{name}", request -> Responses.guarded(() -> deleteChannel(request)))
            .GET("/channels/{name}/messages",
                request -> Responses.guarded(() -> channelMessages(request)))
            .DELETE("/channels/{name}/messages",
                request -> Responses.guarded(() -> clearMessages(request)))
            .POST("/channels/{name}/messages/as-user",
                request -> Responses.guarded(() -> sendAsUser(request)))
            .PATCH("/messages/{name}", request -> Responses.guarded(() -> editMessage(request)))
            .DELETE("/messages/{name}", request -> Responses.guarded(() -> deleteMessage(request)))
            .POST("/channels/{name}/announcement",
                request -> Responses.guarded(() -> updateAnnouncement(request)))
            .DELETE("/channels/{name}/announcement",
                request -> Responses.guarded(() -> deleteAnnouncement(request)))
            .GET("/files", request -> Responses.guarded(() -> files(request)))
            .DELETE("/files/{name}", request -> Responses.guarded(() -> deleteFile(request)))
            .GET("/users", request -> Responses.guarded(() -> users(request)))
            .GET("/storage", request -> Responses.guarded(() -> storage()))
            .POST("/maintenance/cleanup", request -> Responses.guarded(() -> cleanup()))
            .build();
    }

    private Mono<ServerResponse> overview(ServerRequest request) {
        return Mono.zip(
                channelService.listAll().collectList(),
                client.countBy(ChatMessage.class, ListOptions.builder().build()),
                client.countBy(ChatFile.class, ListOptions.builder().build()),
                fileService.storageStats(),
                settingsService.get(),
                commentWidgetAvailable())
            .flatMap(tuple -> {
                var channels = tuple.getT1();
                var overview = new OverviewView(
                    channels.size(),
                    channels.stream().filter(channel ->
                        channel.getSpec().getType() == ChatChannel.Type.GROUP).count(),
                    channels.stream().filter(channel ->
                        channel.getSpec().getType() == ChatChannel.Type.TOPIC).count(),
                    tuple.getT2(),
                    tuple.getT3(),
                    tuple.getT4(),
                    tuple.getT5(),
                    tuple.getT6());
                return Responses.json(overview);
            });
    }

    private Mono<ServerResponse> settings() {
        return Mono.zip(settingsService.get(), commentWidgetAvailable())
            .flatMap(tuple -> Responses.json(Map.of(
                "settings", tuple.getT1(),
                "commentWidgetAvailable", tuple.getT2(),
                "publicChannel", Constants.PUBLIC_CHANNEL_NAME)));
    }

    private Mono<ServerResponse> channels() {
        return channelService.listAll().concatMap(channel ->
                messageService.countByChannel(channel.getMetadata().getName())
                    .map(count -> toAdminView(channel, count)))
            .collectList()
            .flatMap(items -> Responses.json(Map.of("items", items)));
    }

    private Mono<ServerResponse> createChannel(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> request.bodyToMono(ChannelCommand.class)
                .switchIfEmpty(Mono.error(ChatException.badRequest("缺少频道参数")))
                .flatMap(command -> channelService.create(command.toChannelCommand(
                    identity == null ? null : identity.name()))))
            .flatMap(channel -> {
                eventBus.emitChannel("channel.created", channel.getMetadata().getName());
                return Responses.created(toAdminView(channel, 0L));
            });
    }

    private Mono<ServerResponse> updateChannel(ServerRequest request) {
        return request.bodyToMono(ChannelCommand.class)
            .switchIfEmpty(Mono.error(ChatException.badRequest("缺少频道参数")))
            .flatMap(command -> channelService.update(request.pathVariable("name"),
                command.toChannelCommand(null)))
            .flatMap(channel -> {
                eventBus.emitChannel("channel.updated", channel.getMetadata().getName());
                return messageService.countByChannel(channel.getMetadata().getName())
                    .flatMap(count -> Responses.json(toAdminView(channel, count)));
            });
    }

    private Mono<ServerResponse> deleteChannel(ServerRequest request) {
        var name = request.pathVariable("name");
        return channelService.requireChannelForAdmin(name)
            .flatMap(channel -> {
                if (channel.getSpec().getType() == ChatChannel.Type.PUBLIC
                    || Boolean.TRUE.equals(channel.getSpec().getProtectedChannel())) {
                    return Mono.error(ChatException.badRequest("公共频道不能被解散"));
                }
                return messageService.deleteByChannel(name)
                    .then(client.delete(channel))
                    .doOnNext(ignored -> eventBus.emitChannel("channel.deleted", name));
            })
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> channelMessages(ServerRequest request) {
        var page = parseInt(request.queryParam("page").orElse("1"), 1);
        var size = parseInt(request.queryParam("size").orElse("20"), 20);
        return messageService.adminPage(request.pathVariable("name"), page, size)
            .flatMap(Responses::json);
    }

    private Mono<ServerResponse> clearMessages(ServerRequest request) {
        var name = request.pathVariable("name");
        return messageService.deleteByChannel(name)
            .doOnNext(count -> eventBus.emitChannel("channel.cleared", name))
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> sendAsUser(ServerRequest request) {
        var channelName = request.pathVariable("name");
        return identityService.resolve(request.exchange())
            .flatMap(admin -> request.bodyToMono(SendAsUserCommand.class)
                .switchIfEmpty(Mono.error(ChatException.badRequest("缺少发送参数")))
                .flatMap(command -> {
                    if (!StringUtils.hasText(command.username())) {
                        return Mono.error(ChatException.badRequest("请选择要模拟的用户"));
                    }
                    return messageService.createAsUser(admin, channelName, command.username(),
                        command.content());
                }))
            .flatMap(Responses::created);
    }

    private Mono<ServerResponse> editMessage(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> request.bodyToMono(ChatPublicEndpoint.MessageRequest.class)
                .switchIfEmpty(Mono.error(ChatException.badRequest("消息内容不能为空")))
                .flatMap(body -> messageService.edit(identity, request.pathVariable("name"),
                    body.content(), true)))
            .flatMap(Responses::json);
    }

    private Mono<ServerResponse> deleteMessage(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> messageService.delete(identity, request.pathVariable("name"),
                true))
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> updateAnnouncement(ServerRequest request) {
        var name = request.pathVariable("name");
        return identityService.resolve(request.exchange())
            .flatMap(identity -> request.bodyToMono(AnnouncementCommand.class)
                .switchIfEmpty(Mono.error(ChatException.badRequest("缺少公告内容")))
                .flatMap(command -> channelService.updateAnnouncement(name, command.content(),
                    identity == null ? "system" : identity.name())))
            .flatMap(channel -> {
                eventBus.emitChannel("announcement.updated", name);
                return messageService.countByChannel(name)
                    .flatMap(count -> Responses.json(toAdminView(channel, count)));
            });
    }

    private Mono<ServerResponse> deleteAnnouncement(ServerRequest request) {
        var name = request.pathVariable("name");
        return channelService.updateAnnouncement(name, null, null)
            .flatMap(channel -> {
                eventBus.emitChannel("announcement.updated", name);
                return messageService.countByChannel(name)
                    .flatMap(count -> Responses.json(toAdminView(channel, count)));
            });
    }

    private Mono<ServerResponse> files(ServerRequest request) {
        var channel = request.queryParam("channel").orElse(null);
        var page = parseInt(request.queryParam("page").orElse("1"), 1);
        var size = parseInt(request.queryParam("size").orElse("20"), 20);
        return fileService.adminPage(channel, page, size).flatMap(Responses::json);
    }

    private Mono<ServerResponse> deleteFile(ServerRequest request) {
        return fileService.deleteFile(request.pathVariable("name"))
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> users(ServerRequest request) {
        var keyword = request.queryParam("keyword").orElse("").strip().toLowerCase();
        return client.listAll(User.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.creationTimestamp")))
            .filter(user -> {
                if (keyword.isEmpty()) {
                    return true;
                }
                var name = user.getMetadata().getName() == null ? ""
                    : user.getMetadata().getName().toLowerCase();
                var displayName = user.getSpec().getDisplayName() == null ? ""
                    : user.getSpec().getDisplayName().toLowerCase();
                return name.contains(keyword) || displayName.contains(keyword);
            })
            .take(30)
            .map(user -> new UserView(user.getMetadata().getName(),
                user.getSpec().getDisplayName(), user.getSpec().getAvatar(),
                user.getSpec().getEmail()))
            .collectList()
            .flatMap(items -> Responses.json(Map.of("items", items)));
    }

    private Mono<ServerResponse> storage() {
        return fileService.storageStats().flatMap(Responses::json);
    }

    private Mono<ServerResponse> cleanup() {
        return Mono.zip(fileService.cleanupExpired(), fileService.cleanupTemp(),
                guestService.cleanupInactive(Duration.ofDays(90)))
            .flatMap(tuple -> Responses.json(Map.of(
                "expiredFiles", tuple.getT1(),
                "tempFiles", tuple.getT2(),
                "inactiveGuests", tuple.getT3())));
    }

    private Mono<Boolean> commentWidgetAvailable() {
        return client.fetch(Plugin.class, "PluginCommentWidget")
            .map(plugin -> Boolean.TRUE.equals(plugin.getSpec().getEnabled()))
            .defaultIfEmpty(false)
            .onErrorReturn(false);
    }

    private AdminChannelView toAdminView(ChatChannel channel, long messageCount) {
        var spec = channel.getSpec();
        var announcement = spec.getAnnouncement() == null ? null
            : new ChatPublicEndpoint.AnnouncementView(spec.getAnnouncement().getContent(),
                spec.getAnnouncement().getUpdatedAt(),
                spec.getAnnouncement().getUpdatedBy());
        return new AdminChannelView(
            channel.getMetadata().getName(),
            spec.getType() == null ? "GROUP" : spec.getType().name(),
            spec.getDisplayName(),
            spec.getDescription(),
            spec.getAvatar(),
            spec.getOwner(),
            spec.getVisibility() == null ? "AUTHENTICATED" : spec.getVisibility().name(),
            spec.getPostPolicy() == null ? "AUTHENTICATED" : spec.getPostPolicy().name(),
            spec.getMembers() == null ? List.of() : spec.getMembers(),
            announcement,
            spec.getComposerMode() == null ? "INHERIT" : spec.getComposerMode().name(),
            Boolean.TRUE.equals(spec.getProtectedChannel()),
            spec.getLastMessageAt(),
            channel.getMetadata().getCreationTimestamp(),
            messageCount);
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record ChannelCommand(String type, String displayName, String description,
                                 String avatar, String visibility, String postPolicy,
                                 List<String> members, String composerMode) {
        ChannelService.ChannelCreateCommand toChannelCommand(String owner) {
            return new ChannelService.ChannelCreateCommand(
                parseType(type), displayName, description, avatar,
                parsePolicy(visibility, ChatChannel.AccessPolicy.AUTHENTICATED),
                parsePolicy(postPolicy, ChatChannel.AccessPolicy.AUTHENTICATED),
                members,
                parseComposerMode(composerMode), owner);
        }

        private static ChatChannel.Type parseType(String value) {
            if (!StringUtils.hasText(value)) {
                return ChatChannel.Type.GROUP;
            }
            try {
                var type = ChatChannel.Type.valueOf(value.toUpperCase());
                return type == ChatChannel.Type.PUBLIC ? ChatChannel.Type.GROUP : type;
            } catch (IllegalArgumentException e) {
                throw ChatException.badRequest("未知的频道类型: " + value);
            }
        }

        private static ChatChannel.AccessPolicy parsePolicy(String value,
                                                            ChatChannel.AccessPolicy fallback) {
            if (!StringUtils.hasText(value)) {
                return fallback;
            }
            try {
                return ChatChannel.AccessPolicy.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ChatException.badRequest("未知的权限设置: " + value);
            }
        }

        private static ChatChannel.ComposerMode parseComposerMode(String value) {
            if (!StringUtils.hasText(value)) {
                return ChatChannel.ComposerMode.INHERIT;
            }
            try {
                return ChatChannel.ComposerMode.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ChatException.badRequest("未知的输入模式: " + value);
            }
        }
    }

    public record SendAsUserCommand(String username, String content) {
    }

    public record AnnouncementCommand(String content) {
    }

    public record UserView(String name, String displayName, String avatar, String email) {
    }

    public record OverviewView(long channelCount, long groupCount, long topicCount,
                               long messageCount, long fileCount, FileService.StorageStats storage,
                               ChatSettings settings, boolean commentWidgetAvailable) {
    }

    public record AdminChannelView(String name, String type, String displayName,
                                   String description, String avatar, String owner,
                                   String visibility, String postPolicy, List<String> members,
                                   ChatPublicEndpoint.AnnouncementView announcement,
                                   String composerMode, boolean protectedChannel,
                                   Instant lastMessageAt, Instant createdAt, long messageCount) {
    }
}
