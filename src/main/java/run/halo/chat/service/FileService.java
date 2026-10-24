package run.halo.chat.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.chat.Constants;
import run.halo.chat.config.ChatSettings;
import run.halo.chat.config.SettingsService;
import run.halo.chat.extension.ChatChannel;
import run.halo.chat.extension.ChatFile;
import run.halo.chat.extension.ChatMessage;
import run.halo.chat.support.ChatException;

/**
 * Upload, download, quota and expiry management for chat files.
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileService {

    private static final String INDEX_CHANNEL = "spec.channel";

    private final ReactiveExtensionClient client;
    private final ChannelService channelService;
    private final MessageService messageService;
    private final FileStore fileStore;
    private final SettingsService settingsService;
    private final ChatEventBus eventBus;

    /** Recomputes the used bytes from the persisted ChatFile resources. */
    public Mono<Long> initializeQuota() {
        return client.listAll(ChatFile.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.creationTimestamp")))
            .filter(file -> file.getMetadata().getDeletionTimestamp() == null)
            .map(file -> file.getSpec().getSize() == null ? 0L : file.getSpec().getSize())
            .reduce(0L, Long::sum)
            .doOnNext(fileStore::resetUsedBytes);
    }

    public Mono<MessageService.MessageView> upload(Identity identity, String channelName,
                                                   FilePart filePart, String caption) {
        return settingsService.get().flatMap(settings -> {
            if (identity == null || identity.isAnonymous()) {
                return Mono.error(ChatException.unauthorized("请先输入昵称或登录后再上传文件"));
            }
            if (identity.isGuest() && !Boolean.TRUE.equals(settings.getGeneral().getGuestUploadEnabled())) {
                return Mono.error(ChatException.forbidden("游客不能上传文件，请登录后再试"));
            }
            return channelService.requirePostable(identity, channelName)
                .flatMap(channel -> {
                    checkFileType(filePart);
                    return fileStore.save(filePart, settings.getFiles().maxFileSizeBytes())
                        .flatMap(saved -> persistFile(identity, channel, saved, caption, settings));
                });
        });
    }

    public Mono<FileDownload> prepareDownload(Identity identity, String name) {
        return client.fetch(ChatFile.class, name)
            .switchIfEmpty(Mono.error(ChatException.notFound("文件不存在")))
            .flatMap(file -> channelService.requireReadable(identity, file.getSpec().getChannel())
                .flatMap(channel -> {
                    if (file.getSpec().getExpiresAt() != null
                        && file.getSpec().getExpiresAt().isBefore(Instant.now())) {
                        return Mono.error(ChatException.gone("文件已过期"));
                    }
                    var path = fileStore.resolveSafe(file.getSpec().getStorageKey());
                    return Mono.fromCallable(() -> {
                        if (!Files.exists(path)) {
                            throw ChatException.notFound("文件已被删除");
                        }
                        var mediaType = file.getSpec().getMediaType();
                        return new FileDownload(path, file.getSpec().getFilename(),
                            mediaType, file.getSpec().getSize() == null ? Files.size(path)
                            : file.getSpec().getSize(),
                            mediaType != null && mediaType.startsWith("image/"));
                    }).subscribeOn(Schedulers.boundedElastic());
                }));
    }

    public Mono<AdminFilePage> adminPage(String channelName, int page, int size) {
        var pageSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        var options = ListOptions.builder();
        if (StringUtils.hasText(channelName)) {
            options.fieldQuery(Queries.equal(INDEX_CHANNEL, channelName));
        }
        return client.listBy(ChatFile.class, options.build(),
                PageRequestImpl.of(Math.max(1, page), pageSize,
                    Sort.by(Sort.Order.desc("metadata.creationTimestamp"))))
            .map(result -> {
                var rawItems = result.getItems();
                var visibleItems = rawItems.stream()
                    .filter(file -> file.getMetadata().getDeletionTimestamp() == null)
                    .map(this::toView).toList();
                var hidden = rawItems.size() - visibleItems.size();
                return new AdminFilePage(visibleItems, result.getPage(), result.getSize(),
                    Math.max(0, result.getTotal() - hidden));
            });
    }

    /** Admin operation: removes the file, updates or deletes the carrying message. */
    public Mono<Void> deleteFile(String name) {
        return client.fetch(ChatFile.class, name)
            .switchIfEmpty(Mono.error(ChatException.notFound("文件不存在")))
            .flatMap(file -> {
                var messageName = file.getSpec().getMessageName();
                return fileStore.deleteFileRecord(file)
                    .then(Mono.defer(() -> {
                        if (!StringUtils.hasText(messageName)) {
                            return Mono.empty();
                        }
                        return client.fetch(ChatMessage.class, messageName)
                            .flatMap(message -> {
                                var attachments = message.getSpec().getAttachments();
                                if (attachments != null) {
                                    attachments.removeIf(attachment ->
                                        name.equals(attachment.getFileRef()));
                                }
                                if (attachments == null
                                    || (attachments.isEmpty()
                                        && !StringUtils.hasText(message.getSpec().getContent()))) {
                                    return messageService.deleteMessage(message, false);
                                }
                                return client.update(message).flatMap(updated -> {
                                    eventBus.emitMessage("message.updated",
                                        updated.getSpec().getChannel(),
                                        messageService.toView(updated));
                                    return Mono.empty();
                                });
                            });
                    }));
            })
            .then();
    }

    /** Deletes expired files and marks the attachment inside the message as expired. */
    public Mono<Long> cleanupExpired() {
        var now = Instant.now();
        return client.listAll(ChatFile.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.creationTimestamp")))
            .filter(file -> file.getSpec().getExpiresAt() != null
                && file.getSpec().getExpiresAt().isBefore(now))
            .flatMap(file -> expireFile(file).thenReturn(1L))
            .reduce(0L, Long::sum);
    }

    public Mono<StorageStats> storageStats() {
        return settingsService.files().flatMap(files -> client.listAll(ChatFile.class,
                ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.creationTimestamp")))
            .filter(file -> file.getMetadata().getDeletionTimestamp() == null)
            .map(file -> file.getSpec().getSize() == null ? 0L : file.getSpec().getSize())
            .reduce(0L, Long::sum)
            .map(recorded -> new StorageStats(fileStore.usedBytes(), files.maxFileSizeBytes(),
                files.maxTotalSizeBytes(), files.getExpireDays(),
                fileStore.usedBytes(), fileStore.root().toString())));
    }

    /** Removes the temp files left over by interrupted uploads. */
    public Mono<Long> cleanupTemp() {
        return fileStore.cleanupTempFiles();
    }

    private Mono<MessageService.MessageView> persistFile(Identity identity, ChatChannel channel,
                                                         FileStore.SavedFile saved,
                                                         String caption, ChatSettings settings) {
        var maxTotal = settings.getFiles().maxTotalSizeBytes();
        if (!fileStore.reserve(saved.size(), maxTotal)) {
            return Mono.fromRunnable(() -> {
                    try {
                        Files.deleteIfExists(fileStore.resolveSafe(saved.storageKey()));
                    } catch (IOException ignored) {
                        // best effort
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .then(Mono.error(ChatException.payloadTooLarge("聊天文件存储空间已满")));
        }
        var file = new ChatFile();
        var metadata = new Metadata();
        metadata.setGenerateName("chat-file-");
        file.setMetadata(metadata);
        var spec = new ChatFile.Spec();
        spec.setChannel(channel.getMetadata().getName());
        spec.setUploaderType(identity.isGuest()
            ? ChatFile.UploaderType.GUEST : ChatFile.UploaderType.USER);
        spec.setUploaderName(identity.name());
        spec.setUploaderDisplayName(identity.displayName());
        spec.setFilename(saved.filename());
        spec.setSize(saved.size());
        spec.setStorageKey(saved.storageKey());
        spec.setSha256(saved.sha256());
        var expireDays = settings.getFiles().getExpireDays();
        if (expireDays != null && expireDays > 0) {
            spec.setExpiresAt(Instant.now().plus(expireDays, ChronoUnit.DAYS));
        }
        file.setSpec(spec);
        return client.create(file)
            .flatMap(created -> messageService.createFileMessage(identity, channel, created, caption)
                .map(message -> {
                    var annotations = created.getMetadata().getAnnotations();
                    if (annotations == null) {
                        created.getMetadata().setAnnotations(new java.util.HashMap<>());
                        annotations = created.getMetadata().getAnnotations();
                    }
                    annotations.put("chat.halo.run/message-name", message.name());
                    created.getSpec().setMessageName(message.name());
                    client.update(created).subscribe(ignored -> {
                    }, error -> log.debug("Failed to link chat file to message", error));
                    return message;
                }))
            .onErrorResume(error -> {
                fileStore.release(saved.size());
                return client.fetch(ChatFile.class, file.getMetadata().getName())
                    .flatMap(fileStore::deleteFileRecord)
                    .onErrorResume(ignored -> Mono.empty())
                    .then(Mono.error(error instanceof ChatException chatException
                        ? chatException
                        : new ChatException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            "文件上传失败: " + error.getMessage())));
            });
    }

    private Mono<Void> expireFile(ChatFile file) {
        var messageName = file.getSpec().getMessageName();
        var channelName = file.getSpec().getChannel();
        return fileStore.deleteFileRecord(file)
            .then(Mono.defer(() -> {
                if (!StringUtils.hasText(messageName)) {
                    return Mono.empty();
                }
                return client.fetch(ChatMessage.class, messageName)
                    .flatMap(message -> {
                        var attachments = message.getSpec().getAttachments();
                        if (attachments != null) {
                            attachments.stream()
                                .filter(attachment -> file.getMetadata().getName()
                                    .equals(attachment.getFileRef()))
                                .forEach(attachment -> attachment.setExpired(true));
                        }
                        return client.update(message)
                            .doOnNext(updated -> eventBus.emitMessage("message.updated",
                                channelName, messageService.toView(updated)));
                    })
                    .onErrorResume(error -> {
                        log.debug("Failed to mark expired attachment on message {}", messageName,
                            error);
                        return Mono.empty();
                    });
            }))
            .then();
    }

    private void checkFileType(FilePart filePart) {
        var filename = filePart.filename();
        if (!StringUtils.hasText(filename)) {
            throw ChatException.badRequest("缺少文件名");
        }
        var name = FileStore.sanitizeFilename(filename).toLowerCase();
        var blocked = List.of(".exe", ".bat", ".cmd", ".com", ".scr", ".msi", ".ps1", ".vbs",
            ".js", ".jsp", ".php", ".sh", ".jar", ".class");
        if (blocked.stream().anyMatch(name::endsWith)) {
            throw ChatException.badRequest("出于安全考虑，不能上传该类型的文件");
        }
    }

    private ChatFileView toView(ChatFile file) {
        var spec = file.getSpec();
        return new ChatFileView(file.getMetadata().getName(), spec.getChannel(),
            spec.getMessageName(), spec.getFilename(), spec.getSize(), spec.getMediaType(),
            spec.getUploaderType() == null ? "USER" : spec.getUploaderType().name(),
            spec.getUploaderName(), spec.getUploaderDisplayName(), spec.getExpiresAt(),
            file.getMetadata().getCreationTimestamp());
    }

    private static long nullSafe(Long value) {
        return value == null ? 0 : value;
    }

    /** Download payload. */
    public record FileDownload(Path path, String filename, String mediaType, long size,
                               boolean image) {
    }

    public record ChatFileView(String name, String channel, String messageName, String filename,
                               Long size, String mediaType, String uploaderType,
                               String uploaderName, String uploaderDisplayName,
                               Instant expiresAt, Instant createdAt) {
    }

    public record AdminFilePage(List<ChatFileView> items, int page, int size, long total) {
    }

    public record StorageStats(long usedBytes, Long maxFileSize, Long maxTotalSize,
                               Integer expireDays, long diskUsedBytes, String storageRoot) {
    }
}
