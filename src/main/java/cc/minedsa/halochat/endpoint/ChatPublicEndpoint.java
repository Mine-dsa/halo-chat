package cc.minedsa.halochat.endpoint;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import cc.minedsa.halochat.Constants;
import cc.minedsa.halochat.config.ChatSettings;
import cc.minedsa.halochat.config.SettingsService;
import cc.minedsa.halochat.extension.ChatChannel;
import cc.minedsa.halochat.extension.ChatMessage;
import cc.minedsa.halochat.service.ChannelService;
import cc.minedsa.halochat.service.ChatEvent;
import cc.minedsa.halochat.service.ChatEventBus;
import cc.minedsa.halochat.service.FileService;
import cc.minedsa.halochat.service.GuestService;
import cc.minedsa.halochat.service.Identity;
import cc.minedsa.halochat.service.IdentityService;
import cc.minedsa.halochat.service.MessageService;
import cc.minedsa.halochat.support.ChatException;
import cc.minedsa.halochat.support.Responses;

/**
 * Public chat API used by the console chat page and by the standalone
 * front-site page (including guests).
 *
 * <p>Halo RBAC gates the group; fine grained channel policies are enforced by
 * the services.</p>
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPublicEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final IdentityService identityService;
    private final GuestService guestService;
    private final ChannelService channelService;
    private final MessageService messageService;
    private final FileService fileService;
    private final ChatEventBus eventBus;
    private final SettingsService settingsService;

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion(Constants.PUBLIC_API_GROUP, Constants.API_VERSION);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/me", request -> Responses.guarded(() -> me(request)))
            .POST("/guest-sessions", request -> Responses.guarded(() -> guestSession(request)))
            .GET("/channels", request -> Responses.guarded(() -> channels(request)))
            .GET("/channels/{channel}", request -> Responses.guarded(() -> channel(request)))
            .GET("/channels/{channel}/messages",
                request -> Responses.guarded(() -> messages(request)))
            .POST("/channels/{channel}/messages",
                request -> Responses.guarded(() -> sendMessage(request)))
            .POST("/channels/{channel}/files",
                request -> Responses.guarded(() -> uploadFile(request)))
            .GET("/files/{name}", request -> Responses.guarded(() -> download(request)))
            .PATCH("/messages/{name}", request -> Responses.guarded(() -> editMessage(request)))
            .DELETE("/messages/{name}", request -> Responses.guarded(() -> deleteMessage(request)))
            .GET("/events", request -> Responses.guarded(() -> events(request)))
            .build();
    }

    private Mono<ServerResponse> me(ServerRequest request) {
        return Mono.zip(identityService.resolve(request.exchange()), settingsService.get(),
                commentWidgetAvailable())
            .flatMap(tuple -> {
                var identity = tuple.getT1();
                var settings = tuple.getT2();
                var view = new MeView(
                    identity.type().name(),
                    identity.name(),
                    identity.displayName(),
                    identity.avatar(),
                    identity.canManage(),
                    Boolean.TRUE.equals(settings.getGeneral().getGuestEnabled()),
                    Boolean.TRUE.equals(settings.getGeneral().getGuestUploadEnabled()),
                    settings.getGeneral().getMessageMaxLength(),
                    settings.getGeneral().getGuestNameMaxLength(),
                    settings.getComposer().getMode(),
                    settings.getFiles().maxFileSizeBytes(),
                    settings.getFiles().maxTotalSizeBytes(),
                    settings.getFiles().getExpireDays(),
                    tuple.getT3());
                return Responses.json(view);
            });
    }

    private Mono<ServerResponse> guestSession(ServerRequest request) {
        var exchange = request.exchange();
        return request.bodyToMono(NicknameRequest.class)
            .switchIfEmpty(Mono.error(ChatException.badRequest("请输入昵称")))
            .flatMap(body -> guestService.currentGuest(exchange)
                .flatMap(guest -> guestService.renameSession(body.nickname(), exchange))
                .switchIfEmpty(Mono.defer(() ->
                    guestService.createSession(body.nickname(), exchange))))
            .flatMap(session -> ServerResponse.ok()
                .header(HttpHeaders.SET_COOKIE, session.setCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GuestSessionView(session.guestId(), session.nickname())));
    }

    private Mono<ServerResponse> channels(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> channelService.listVisible(identity)
                .concatMap(channel -> channelService.canPost(identity, channel)
                    .map(canPost -> toView(channel, canPost)))
                .collectList()
                .flatMap(views -> Responses.json(Map.of("items", views))));
    }

    private Mono<ServerResponse> channel(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> channelService
                .requireReadable(identity, request.pathVariable("channel"))
                .flatMap(channel -> channelService.canPost(identity, channel)
                    .map(canPost -> toView(channel, canPost)))
                .flatMap(Responses::json));
    }

    private Mono<ServerResponse> messages(ServerRequest request) {
        var limit = parseInt(request.queryParam("limit").orElse("30"), 30);
        var before = parseInstant(request.queryParam("before").orElse(null));
        return identityService.resolve(request.exchange())
            .flatMap(identity -> messageService
                .page(identity, request.pathVariable("channel"), before, limit)
                .flatMap(Responses::json));
    }

    private Mono<ServerResponse> sendMessage(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> request.bodyToMono(MessageRequest.class)
                .switchIfEmpty(Mono.error(ChatException.badRequest("消息内容不能为空")))
                .flatMap(body -> messageService.createText(identity,
                    request.pathVariable("channel"), body.content()))
                .flatMap(Responses::created));
    }

    private Mono<ServerResponse> uploadFile(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> request.multipartData().flatMap(parts -> {
                var filePart = firstFilePart(parts);
                if (filePart == null) {
                    return Mono.error(ChatException.badRequest("请选择要上传的文件"));
                }
                var caption = firstFormValue(parts, "caption");
                return fileService.upload(identity, request.pathVariable("channel"),
                    filePart, caption);
            }))
            .flatMap(Responses::created);
    }

    private Mono<ServerResponse> download(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> fileService.prepareDownload(identity, request.pathVariable("name")))
            .flatMap(download -> {
                var builder = ServerResponse.ok()
                    .contentType(MediaType.parseMediaType(download.mediaType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : download.mediaType()))
                    .contentLength(download.size())
                    .cacheControl(CacheControl.noStore());
                var disposition = download.image()
                    ? ContentDisposition.inline()
                    : ContentDisposition.attachment();
                var header = disposition.filename(download.filename(), StandardCharsets.UTF_8)
                    .build().toString();
                return builder.header(HttpHeaders.CONTENT_DISPOSITION, header)
                    .body(BodyInserters.fromResource(new FileSystemResource(download.path())));
            });
    }

    private Mono<ServerResponse> editMessage(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> request.bodyToMono(MessageRequest.class)
                .switchIfEmpty(Mono.error(ChatException.badRequest("消息内容不能为空")))
                .flatMap(body -> messageService.edit(identity, request.pathVariable("name"),
                    body.content(), false))
                .flatMap(Responses::json));
    }

    private Mono<ServerResponse> deleteMessage(ServerRequest request) {
        return identityService.resolve(request.exchange())
            .flatMap(identity -> messageService.delete(identity, request.pathVariable("name"),
                    false))
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> events(ServerRequest request) {
        var channelName = request.queryParam("channel").orElse(null);
        if (!StringUtils.hasText(channelName)) {
            return Mono.error(ChatException.badRequest("缺少 channel 参数"));
        }
        return identityService.resolve(request.exchange())
            .flatMap(identity -> channelService.requireReadable(identity, channelName))
            .flatMap(channel -> {
                Flux<ChatEvent> source = eventBus.subscribe(channelName);
                var connected = org.springframework.http.codec.ServerSentEvent
                    .<ChatEvent>builder().comment("connected").build();
                var pings = Flux.interval(Duration.ofSeconds(10))
                    .map(tick -> org.springframework.http.codec.ServerSentEvent
                        .<ChatEvent>builder().comment("ping").build());
                Flux<org.springframework.http.codec.ServerSentEvent<ChatEvent>> events =
                    source.map(event -> org.springframework.http.codec.ServerSentEvent
                            .<ChatEvent>builder(event)
                            .id(event.at() == null ? null : event.at().toString())
                            .build())
                        .mergeWith(pings)
                        .startWith(connected);
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noCache())
                    .header("X-Accel-Buffering", "no")
                    .body(events, new ParameterizedTypeReference<>() {
                    });
            });
    }

    private ChannelView toView(ChatChannel channel, boolean canPost) {
        var spec = channel.getSpec();
        var announcement = spec.getAnnouncement() == null ? null
            : new AnnouncementView(spec.getAnnouncement().getContent(),
                spec.getAnnouncement().getUpdatedAt(),
                spec.getAnnouncement().getUpdatedBy());
        return new ChannelView(
            channel.getMetadata().getName(),
            spec.getType() == null ? "GROUP" : spec.getType().name(),
            spec.getDisplayName(),
            spec.getDescription(),
            spec.getAvatar(),
            spec.getOwner(),
            spec.getVisibility() == null ? "AUTHENTICATED" : spec.getVisibility().name(),
            spec.getPostPolicy() == null ? "AUTHENTICATED" : spec.getPostPolicy().name(),
            spec.getMembers() == null ? 0 : spec.getMembers().size(),
            announcement,
            spec.getComposerMode() == null ? "INHERIT" : spec.getComposerMode().name(),
            Boolean.TRUE.equals(spec.getProtectedChannel()),
            canPost,
            spec.getLastMessageAt(),
            channel.getMetadata().getCreationTimestamp());
    }

    private Mono<Boolean> commentWidgetAvailable() {
        return client.fetch(Plugin.class, "PluginCommentWidget")
            .map(plugin -> Boolean.TRUE.equals(plugin.getSpec().getEnabled()))
            .defaultIfEmpty(false)
            .onErrorReturn(false);
    }

    private static FilePart firstFilePart(
        org.springframework.util.MultiValueMap<String, org.springframework.http.codec.multipart.Part> parts) {
        for (var entry : parts.entrySet()) {
            for (var part : entry.getValue()) {
                if (part instanceof FilePart filePart) {
                    return filePart;
                }
            }
        }
        return null;
    }

    private static String firstFormValue(
        org.springframework.util.MultiValueMap<String, org.springframework.http.codec.multipart.Part> parts,
        String name) {
        var partition = parts.getFirst(name);
        if (partition instanceof FormFieldPart formFieldPart) {
            return formFieldPart.value();
        }
        return null;
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    public record NicknameRequest(String nickname) {
    }

    public record MessageRequest(String content) {
    }

    public record GuestSessionView(String guestId, String nickname) {
    }

    public record MeView(String type, String name, String displayName, String avatar,
                         boolean canManage, boolean guestEnabled, boolean guestUploadEnabled,
                         int messageMaxLength, int guestNameMaxLength, String composerMode,
                         Long maxFileSize, Long maxTotalSize, Integer expireDays,
                         boolean commentWidgetAvailable) {
    }

    public record ChannelView(String name, String type, String displayName, String description,
                              String avatar, String owner, String visibility, String postPolicy,
                              int memberCount, AnnouncementView announcement, String composerMode,
                              boolean protectedChannel, boolean canPost, Instant lastMessageAt,
                              Instant createdAt) {
    }

    public record AnnouncementView(String content, Instant updatedAt, String updatedBy) {
    }
}
