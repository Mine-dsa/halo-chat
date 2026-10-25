package cc.minedsa.halochat.service;

import java.time.Instant;
import java.util.ArrayList;
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
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import cc.minedsa.halochat.Constants;
import cc.minedsa.halochat.config.ChatSettings;
import cc.minedsa.halochat.config.SettingsService;
import cc.minedsa.halochat.extension.ChatChannel;
import cc.minedsa.halochat.extension.ChatFile;
import cc.minedsa.halochat.extension.ChatGuest;
import cc.minedsa.halochat.extension.ChatMessage;
import cc.minedsa.halochat.support.ChatException;
import cc.minedsa.halochat.support.RateLimiter;

/**
 * Message creation, pagination, moderation and comment mirroring.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageService {

    private static final String INDEX_CHANNEL = "spec.channel";

    private final ReactiveExtensionClient client;
    private final ChannelService channelService;
    private final FileStore fileStore;
    private final SettingsService settingsService;
    private final ChatEventBus eventBus;
    private final RateLimiter rateLimiter = new RateLimiter();

    public Mono<MessagePage> page(Identity identity, String channelName, Instant before, int limit) {
        var size = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        return channelService.requireReadable(identity, channelName)
            .flatMap(channel -> {
                var options = ListOptions.builder()
                    .fieldQuery(Queries.equal(INDEX_CHANNEL, channelName));
                if (before != null) {
                    options.andQuery(Queries.lessThan("metadata.creationTimestamp", before));
                }
                return client.listBy(ChatMessage.class, options.build(),
                        PageRequestImpl.of(1, size + 1,
                            Sort.by(Sort.Order.desc("metadata.creationTimestamp"))))
                    .map(result -> {
                        var items = new ArrayList<>(result.getItems());
                        var hasMore = items.size() > size;
                        if (hasMore) {
                            items = new ArrayList<>(items.subList(0, size));
                        }
                        items.removeIf(message -> Boolean.TRUE.equals(message.getSpec().getDeleted())
                            || message.getMetadata().getDeletionTimestamp() != null);
                        var views = items.stream().map(this::toView).toList();
                        var reversed = new ArrayList<>(views);
                        java.util.Collections.reverse(reversed);
                        return new MessagePage(reversed, hasMore);
                    });
            });
    }

    public Mono<MessageView> createText(Identity identity, String channelName, String rawContent) {
        return settingsService.general().flatMap(general -> {
            var content = normalizeContent(rawContent, general.getMessageMaxLength());
            return checkRateLimit(identity, general)
                .then(channelService.requirePostable(identity, channelName))
                .flatMap(channel -> {
                    var message = newMessage(identity, channelName, content);
                    message.getSpec().setSource(ChatMessage.SourceType.BUILTIN);
                    return client.create(message)
                        .flatMap(created -> afterCreate(created, identity));
                });
        });
    }

    public Mono<MessageView> createAsUser(Identity admin, String channelName, String username,
                                          String rawContent) {
        return settingsService.general().flatMap(general -> {
            var content = normalizeContent(rawContent, general.getMessageMaxLength());
            return channelService.requireChannel(channelName)
                .flatMap(channel -> client.fetch(User.class, username)
                    .switchIfEmpty(Mono.error(ChatException.notFound("目标用户不存在")))
                    .flatMap(user -> {
                        var message = newMessage(Identity.Type.USER, username,
                            user.getSpec().getDisplayName() == null
                                ? username : user.getSpec().getDisplayName(),
                            user.getSpec().getAvatar(), channelName, content);
                        message.getSpec().setSource(ChatMessage.SourceType.ADMIN);
                        var annotations = safeAnnotations(message);
                        annotations.put(Constants.ANNO_ON_BEHALF_OF,
                            admin == null ? "unknown" : admin.name());
                        return client.create(message).flatMap(created -> afterCreate(created, null));
                    }));
        });
    }

    public Mono<MessageView> createFileMessage(Identity identity, ChatChannel channel, ChatFile file,
                                               String caption) {
        return settingsService.general().flatMap(general -> {
            var content = StringUtils.hasText(caption)
                ? normalizeContent(caption, general.getMessageMaxLength()) : "";
            var message = newMessage(identity, channel.getMetadata().getName(), content);
            message.getSpec().setSource(ChatMessage.SourceType.BUILTIN);
            var attachment = new ChatMessage.Attachment();
            attachment.setFileRef(file.getMetadata().getName());
            attachment.setName(file.getSpec().getFilename());
            attachment.setSize(file.getSpec().getSize());
            attachment.setMediaType(file.getSpec().getMediaType());
            attachment.setExpired(false);
            message.getSpec().setAttachments(new ArrayList<>(List.of(attachment)));
            return client.create(message)
                .flatMap(created -> {
                    file.getSpec().setMessageName(created.getMetadata().getName());
                    return client.update(file).thenReturn(created);
                })
                .flatMap(created -> afterCreate(created, identity));
        });
    }

    public Mono<MessageView> edit(Identity identity, String messageName, String rawContent,
                                  boolean admin) {
        return settingsService.general().flatMap(general -> {
            var content = normalizeContent(rawContent, general.getMessageMaxLength());
            return client.fetch(ChatMessage.class, messageName)
                .switchIfEmpty(Mono.error(ChatException.notFound("消息不存在")))
                .flatMap(message -> {
                    if (Boolean.TRUE.equals(message.getSpec().getDeleted())) {
                        return Mono.error(ChatException.notFound("消息已被删除"));
                    }
                    if (!admin && !isAuthor(identity, message)) {
                        return Mono.error(ChatException.forbidden("只能修改自己的消息"));
                    }
                    if (!admin && identity.isGuest()) {
                        return Mono.error(ChatException.forbidden("游客不能修改消息"));
                    }
                    message.getSpec().setContent(content);
                    message.getSpec().setEditedAt(Instant.now());
                    message.getSpec().setEditedBy(identity == null ? "system" : identity.name());
                    var annotations = safeAnnotations(message);
                    annotations.put(Constants.ANNO_EDITED_BY,
                        identity == null ? "system" : identity.name());
                    return client.update(message);
                })
                .flatMap(updated -> {
                    var view = toView(updated);
                    eventBus.emitMessage("message.updated", updated.getSpec().getChannel(), view);
                    return Mono.just(view);
                });
        });
    }

    public Mono<Void> delete(Identity identity, String messageName, boolean admin) {
        return client.fetch(ChatMessage.class, messageName)
            .switchIfEmpty(Mono.error(ChatException.notFound("消息不存在")))
            .flatMap(message -> {
                if (!admin && !isAuthor(identity, message)) {
                    return Mono.error(ChatException.forbidden("只能删除自己的消息"));
                }
                if (!admin && identity.isGuest()) {
                    return Mono.error(ChatException.forbidden("游客不能删除消息"));
                }
                return deleteMessage(message, true).thenReturn(message);
            })
            .then();
    }

    /** Deletes a message and its files, used by admin/cascade operations. */
    public Mono<Void> deleteMessage(ChatMessage message, boolean deleteSourceComment) {
        var channelName = message.getSpec().getChannel();
        var view = toView(message);
        return deleteAttachments(message)
            .then(deleteMirroredComment(message, deleteSourceComment))
            .then(client.delete(message))
            .doOnNext(ignored -> eventBus.emitMessage("message.deleted",
                channelName, view))
            .then();
    }

    /** Deletes all messages of a channel. Used by the dissolve flow. */
    public Mono<Long> deleteByChannel(String channelName) {
        return client.listAll(ChatMessage.class, ListOptions.builder()
                    .fieldQuery(Queries.equal(INDEX_CHANNEL, channelName)).build(),
                Sort.by(Sort.Order.asc("metadata.creationTimestamp")))
            .flatMap(message -> deleteMessage(message, true).thenReturn(1L))
            .reduce(0L, Long::sum);
    }

    public Mono<MessageView> upsertFromComment(Comment comment, ChatChannel channel) {
        var commentName = comment.getMetadata().getName();
        var messageName = "cm-" + commentName;
        var spec = comment.getSpec();
        var hidden = Boolean.TRUE.equals(spec.getHidden());
        var approved = !Boolean.FALSE.equals(spec.getApproved());
        if (hidden || !approved) {
            return client.fetch(ChatMessage.class, messageName)
                .flatMap(existing -> deleteMessage(existing, false))
                .then(Mono.empty());
        }
        var content = StringUtils.hasText(spec.getContent()) ? spec.getContent() : spec.getRaw();
        return senderOf(comment).flatMap(sender ->
            client.fetch(ChatMessage.class, messageName)
                .flatMap(existing -> {
                    existing.getSpec().setContent(content);
                    existing.getSpec().setSenderDisplayName(sender.displayName());
                    existing.getSpec().setSenderAvatar(sender.avatar());
                    existing.getSpec().setDeleted(false);
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var message = new ChatMessage();
                    var metadata = new Metadata();
                    metadata.setName(messageName);
                    message.setMetadata(metadata);
                    var messageSpec = new ChatMessage.Spec();
                    messageSpec.setChannel(channel.getMetadata().getName());
                    messageSpec.setSenderType(sender.type());
                    messageSpec.setSenderName(sender.name());
                    messageSpec.setSenderDisplayName(sender.displayName());
                    messageSpec.setSenderAvatar(sender.avatar());
                    messageSpec.setContentType(ChatMessage.ContentType.HTML);
                    messageSpec.setContent(content);
                    messageSpec.setSource(ChatMessage.SourceType.COMMENT);
                    messageSpec.setSourceName(commentName);
                    message.setSpec(messageSpec);
                    return client.create(message);
                }))
                .map(created -> {
                    var view = toView(created);
                    eventBus.emitMessage("message.created", channel.getMetadata().getName(), view);
                    channelService.touchLastMessage(channel.getMetadata().getName(),
                        Instant.now()).subscribe();
                    return view;
                }));
    }

    public Mono<Void> deleteFromComment(String commentName) {
        return client.fetch(ChatMessage.class, "cm-" + commentName)
            .flatMap(message -> deleteMessage(message, false))
            .then();
    }

    public Mono<AdminMessagePage> adminPage(String channelName, int page, int size) {
        var pageSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        var options = ListOptions.builder()
            .fieldQuery(Queries.equal(INDEX_CHANNEL, channelName)).build();
        return client.listBy(ChatMessage.class, options,
                PageRequestImpl.of(Math.max(1, page), pageSize,
                    Sort.by(Sort.Order.desc("metadata.creationTimestamp"))))
            .map(result -> {
                var rawItems = result.getItems();
                var visibleItems = rawItems.stream()
                    .filter(message -> message.getMetadata().getDeletionTimestamp() == null)
                    .map(this::toView).toList();
                // Terminating resources can still be part of the page total until
                // Halo's GC removes them; do not show them to the administrator.
                var hidden = rawItems.size() - visibleItems.size();
                return new AdminMessagePage(visibleItems, result.getPage(), result.getSize(),
                    Math.max(0, result.getTotal() - hidden));
            });
    }

    public Mono<Long> countByChannel(String channelName) {
        return client.countBy(ChatMessage.class, ListOptions.builder()
            .fieldQuery(Queries.equal(INDEX_CHANNEL, channelName)).build());
    }

    public Mono<MessageView> toViewByFetch(String messageName) {
        return client.fetch(ChatMessage.class, messageName).map(this::toView);
    }

    public MessageView toView(ChatMessage message) {
        var spec = message.getSpec();
        var attachments = spec.getAttachments() == null ? List.<AttachmentView>of()
            : spec.getAttachments().stream().map(attachment -> new AttachmentView(
                attachment.getFileRef(),
                attachment.getName(),
                attachment.getSize(),
                attachment.getMediaType(),
                attachment.getMediaType() != null
                    && attachment.getMediaType().startsWith("image/"),
                "/apis/" + Constants.PUBLIC_API_GROUP + "/" + Constants.API_VERSION
                    + "/files/" + attachment.getFileRef(),
                Boolean.TRUE.equals(attachment.getExpired())))
            .toList();
        var annotations = safeAnnotations(message);
        var createdAt = message.getMetadata().getCreationTimestamp();
        return new MessageView(
            message.getMetadata().getName(),
            spec.getChannel(),
            spec.getSenderType() == null ? "USER" : spec.getSenderType().name(),
            spec.getSenderName(),
            spec.getSenderDisplayName(),
            spec.getSenderAvatar(),
            spec.getContentType() == null ? "TEXT" : spec.getContentType().name(),
            Boolean.TRUE.equals(spec.getDeleted()) ? "" : spec.getContent(),
            attachments,
            spec.getSource() == null ? "BUILTIN" : spec.getSource().name(),
            annotations.containsKey(Constants.ANNO_ON_BEHALF_OF),
            createdAt,
            spec.getEditedAt());
    }

    private ChatMessage newMessage(Identity identity, String channelName, String content) {
        return newMessage(identity.type(), identity.name(), identity.displayName(),
            identity.avatar(), channelName, content);
    }

    private ChatMessage newMessage(Identity.Type type, String name, String displayName,
                                   String avatar, String channelName, String content) {
        var message = new ChatMessage();
        var metadata = new Metadata();
        metadata.setGenerateName("chat-msg-");
        message.setMetadata(metadata);
        var spec = new ChatMessage.Spec();
        spec.setChannel(channelName);
        spec.setSenderType(switch (type) {
            case USER -> ChatMessage.SenderType.USER;
            case GUEST -> ChatMessage.SenderType.GUEST;
            case ANONYMOUS -> ChatMessage.SenderType.SYSTEM;
        });
        spec.setSenderName(name);
        spec.setSenderDisplayName(displayName);
        spec.setSenderAvatar(avatar);
        spec.setContentType(ChatMessage.ContentType.TEXT);
        spec.setContent(content);
        spec.setSource(ChatMessage.SourceType.BUILTIN);
        message.setSpec(spec);
        return message;
    }

    private Mono<MessageView> afterCreate(ChatMessage message, Identity identity) {
        var view = toView(message);
        eventBus.emitMessage("message.created", message.getSpec().getChannel(), view);
        channelService.touchLastMessage(message.getSpec().getChannel(),
            message.getMetadata().getCreationTimestamp() == null
                ? Instant.now() : message.getMetadata().getCreationTimestamp()).subscribe();
        if (identity != null && identity.isGuest()) {
            client.fetch(ChatGuest.class, identity.name())
                .flatMap(guest -> {
                    var spec = guest.getSpec();
                    spec.setMessageCount((spec.getMessageCount() == null
                        ? 0L : spec.getMessageCount()) + 1);
                    spec.setLastSeenAt(Instant.now());
                    return client.update(guest);
                })
                .subscribe(ignored -> {
                }, error -> log.debug("Failed to update guest message count", error));
        }
        return Mono.just(view);
    }

    private Mono<Void> checkRateLimit(Identity identity, ChatSettings.General general) {
        if (identity == null || identity.isAnonymous()) {
            return Mono.error(ChatException.unauthorized("请先输入昵称或登录后再发言"));
        }
        String key;
        int limit;
        if (identity.isUser()) {
            key = "msg:user:" + identity.name();
            limit = general.getUserRateLimit() == null ? 120 : general.getUserRateLimit();
        } else {
            key = "msg:guest:" + identity.name();
            limit = general.getGuestRateLimit() == null ? 20 : general.getGuestRateLimit();
        }
        return rateLimiter.tryAcquire(key, limit)
            ? Mono.empty()
            : Mono.error(ChatException.tooManyRequests("发送过于频繁，请稍后再试"));
    }

    private String normalizeContent(String rawContent, Integer maxLength) {
        var content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            throw ChatException.badRequest("消息内容不能为空");
        }
        content = content.replace("\r\n", "\n");
        if (content.codePoints().anyMatch(codePoint ->
            Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')) {
            throw ChatException.badRequest("消息内容包含非法字符");
        }
        var limit = maxLength == null ? 2000 : maxLength;
        if (content.length() > limit) {
            throw ChatException.badRequest("消息不能超过 " + limit + " 个字符");
        }
        return content;
    }

    private Mono<Void> deleteAttachments(ChatMessage message) {
        var attachments = message.getSpec().getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(attachments)
            .map(ChatMessage.Attachment::getFileRef)
            .filter(StringUtils::hasText)
            .concatMap(fileName -> client.fetch(ChatFile.class, fileName)
                .flatMap(fileStore::deleteFileRecord))
            .then();
    }

    private Mono<Void> deleteMirroredComment(ChatMessage message, boolean deleteComment) {
        if (!deleteComment || message.getSpec().getSource() != ChatMessage.SourceType.COMMENT) {
            return Mono.empty();
        }
        var commentName = message.getSpec().getSourceName();
        if (!StringUtils.hasText(commentName)) {
            return Mono.empty();
        }
        return client.fetch(Comment.class, commentName)
            .flatMap(client::delete)
            .onErrorResume(error -> Mono.empty())
            .then();
    }

    private boolean isAuthor(Identity identity, ChatMessage message) {
        if (identity == null || !identity.isAuthenticated()) {
            return false;
        }
        if (message.getSpec().getSource() == ChatMessage.SourceType.COMMENT) {
            return false;
        }
        return Objects.equals(identity.name(), message.getSpec().getSenderName());
    }

    private Mono<Sender> senderOf(Comment comment) {
        var owner = comment.getSpec().getOwner();
        if (owner == null) {
            return Mono.just(new Sender(ChatMessage.SenderType.GUEST, null, "匿名", null));
        }
        if ("User".equalsIgnoreCase(owner.getKind())) {
            if (StringUtils.hasText(owner.getName())) {
                return client.fetch(User.class, owner.getName())
                    .map(user -> new Sender(ChatMessage.SenderType.USER, owner.getName(),
                        owner.getDisplayName() == null ? owner.getName() : owner.getDisplayName(),
                        user.getSpec().getAvatar()))
                    .defaultIfEmpty(new Sender(ChatMessage.SenderType.USER, owner.getName(),
                        owner.getDisplayName(), null));
            }
            return Mono.just(new Sender(ChatMessage.SenderType.USER, null,
                owner.getDisplayName() == null ? "Halo 用户" : owner.getDisplayName(), null));
        }
        var avatar = owner.getAnnotations() == null ? null
            : owner.getAnnotations().get(Comment.CommentOwner.AVATAR_ANNO);
        return Mono.just(new Sender(ChatMessage.SenderType.GUEST,
            owner.getName(), owner.getDisplayName(), avatar));
    }

    private java.util.Map<String, String> safeAnnotations(ChatMessage message) {
        var metadata = message.getMetadata();
        if (metadata.getAnnotations() == null) {
            metadata.setAnnotations(new java.util.HashMap<>());
        }
        return metadata.getAnnotations();
    }

    /** One page of messages, ordered oldest to newest. */
    public record MessagePage(List<MessageView> items, boolean hasMore) {
    }

    /** One page of messages for the admin table. */
    public record AdminMessagePage(List<MessageView> items, int page, int size, long total) {
    }

    public record MessageView(String name, String channel, String senderType,
                              String senderName, String senderDisplayName, String senderAvatar,
                              String contentType, String content, List<AttachmentView> attachments,
                              String source, boolean onBehalf, Instant createdAt,
                              Instant editedAt) {
    }

    public record AttachmentView(String fileRef, String name, Long size, String mediaType,
                                 boolean image, String url, boolean expired) {
    }

    private record Sender(ChatMessage.SenderType type, String name, String displayName,
                          String avatar) {
    }
}
