package run.halo.chat.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginsRootGetter;
import run.halo.chat.extension.ChatFile;
import run.halo.chat.support.ChatException;

/**
 * Low level file storage below {@code {halo.work-dir}/chat-data}.
 *
 * <p>Files are never exposed by a static handler; downloads go through an
 * authenticated plugin endpoint.</p>
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Slf4j
@Component
public class FileStore {

    private final ReactiveExtensionClient client;
    private final Path root;
    private final Path filesRoot;
    private final Path tempRoot;
    private final AtomicLong usedBytes = new AtomicLong();

    public FileStore(ReactiveExtensionClient client,
                     org.springframework.beans.factory.ObjectProvider<PluginsRootGetter> rootGetter,
                     org.springframework.core.env.Environment environment) {
        this.client = client;
        Path workDir = null;
        try {
            var getter = rootGetter.getIfAvailable();
            if (getter != null) {
                var pluginsRoot = getter.get();
                workDir = pluginsRoot == null ? null : pluginsRoot.getParent();
            }
        } catch (Exception e) {
            log.debug("PluginsRootGetter is not available", e);
        }
        if (workDir == null) {
            var configured = environment.getProperty("halo.work-dir", "${user.home}/.halo2");
            try {
                workDir = Path.of(environment.resolvePlaceholders(configured));
            } catch (Exception e) {
                workDir = Path.of(System.getProperty("user.home"), ".halo2");
            }
        }
        this.root = workDir.resolve("chat-data").toAbsolutePath().normalize();
        this.filesRoot = root.resolve("files");
        this.tempRoot = root.resolve("tmp");
        try {
            Files.createDirectories(filesRoot);
            Files.createDirectories(tempRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create chat data directory " + root, e);
        }
        log.info("Chat files will be stored in {}", root);
    }

    public Path root() {
        return root;
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public void resetUsedBytes(long value) {
        usedBytes.set(Math.max(0, value));
    }

    /**
     * Atomically reserves quota. {@code limit <= 0} means unlimited.
     */
    public boolean reserve(long size, long limit) {
        if (size <= 0) {
            return true;
        }
        while (true) {
            var current = usedBytes.get();
            if (limit > 0 && current + size > limit) {
                return false;
            }
            if (usedBytes.compareAndSet(current, current + size)) {
                return true;
            }
        }
    }

    public void release(long size) {
        if (size <= 0) {
            return;
        }
        usedBytes.updateAndGet(current -> Math.max(0, current - size));
    }

    /**
     * Streams a multipart file into the plugin data directory.
     */
    public Mono<SavedFile> save(FilePart part, long maxSize) {
        var originalName = sanitizeFilename(part.filename());
        var extension = extensionOf(originalName);
        var relativeKey = "files/" + LocalDate.now().getYear() + "/"
            + String.format("%02d", LocalDate.now().getMonthValue()) + "/"
            + UUID.randomUUID().toString().replace("-", "") + extension;
        var target = resolveSafe(relativeKey);
        return Mono.fromCallable(() -> Files.createTempFile(tempRoot, "upload-", ".part"))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(tempFile -> part.transferTo(tempFile.toFile())
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromCallable(() -> {
                    try {
                        var size = Files.size(tempFile);
                        if (maxSize > 0 && size > maxSize) {
                            throw ChatException.payloadTooLarge("文件超过单文件大小限制");
                        }
                        if (size == 0) {
                            throw ChatException.badRequest("不能上传空文件");
                        }
                        Files.createDirectories(target.getParent());
                        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                        var digest = sha256(target);
                        return new SavedFile(relativeKey, size, digest, originalName);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                }).subscribeOn(Schedulers.boundedElastic())))
            .onErrorMap(error -> {
                if (error instanceof ChatException chatException) {
                    return chatException;
                }
                return new ChatException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件保存失败: " + error.getMessage());
            });
    }

    public Path resolveSafe(String storageKey) {
        if (!StringUtils.hasText(storageKey) || storageKey.contains("..")) {
            throw ChatException.badRequest("非法的文件路径");
        }
        var resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw ChatException.forbidden("非法的文件路径");
        }
        return resolved;
    }

    /** Deletes the bytes and the ChatFile resource. */
    public Mono<Void> deleteFileRecord(ChatFile file) {
        return Mono.fromRunnable(() -> {
                try {
                    Files.deleteIfExists(resolveSafe(file.getSpec().getStorageKey()));
                } catch (IOException e) {
                    log.warn("Failed to delete chat file {}", file.getMetadata().getName(), e);
                }
                release(file.getSpec().getSize() == null ? 0 : file.getSpec().getSize());
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then(client.delete(file))
            .then();
    }

    public Mono<Long> cleanupTempFiles() {
        return Mono.fromCallable(() -> {
            var threshold = System.currentTimeMillis() - 3600_000L;
            long deleted = 0;
            try (var stream = Files.list(tempRoot)) {
                for (var path : stream.toList()) {
                    try {
                        if (Files.getLastModifiedTime(path).toMillis() < threshold) {
                            Files.deleteIfExists(path);
                            deleted++;
                        }
                    } catch (IOException ignored) {
                        // ignore single file failures
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to cleanup chat temp directory", e);
            }
            return deleted;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    public static String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "file";
        }
        var name = filename.replace('\\', '/');
        var index = name.lastIndexOf('/');
        if (index >= 0) {
            name = name.substring(index + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            return "file";
        }
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    public static String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        var index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        var extension = filename.substring(index).toLowerCase();
        return extension.matches("^\\.[a-z0-9]{1,10}$") ? extension : "";
    }

    /** Result of storing one multipart file. */
    public record SavedFile(String storageKey, long size, String sha256, String filename) {
    }
}
