package cc.minedsa.halochat.service;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import cc.minedsa.halochat.Constants;
import cc.minedsa.halochat.config.SettingsService;
import cc.minedsa.halochat.extension.ChatGuest;
import cc.minedsa.halochat.support.ChatException;
import cc.minedsa.halochat.support.RateLimiter;

/**
 * Creates and validates guest sessions. Guests never authenticate with Halo;
 * the browser receives a signed HttpOnly cookie and the nickname lives on the
 * server so it cannot be spoofed on every message.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> RESERVED_NAMES = List.of(
        "admin", "administrator", "root", "system", "halo", "anonymoususer", "游客", "系统"
    );

    private final ReactiveExtensionClient client;
    private final SettingsService settingsService;

    private final RateLimiter rateLimiter = new RateLimiter();
    private final AtomicReference<byte[]> signingKey = new AtomicReference<>();

    /** Loads (or creates) the HMAC key used to sign guest cookies. */
    public Mono<Void> ensureSecret() {
        return signingKey().then();
    }

    public Mono<GuestSession> createSession(String rawNickname, ServerWebExchange exchange) {
        var ip = clientIp(exchange);
        var userAgent = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
        return settingsService.general().flatMap(general -> {
            if (!Boolean.TRUE.equals(general.getGuestEnabled())) {
                return Mono.error(ChatException.forbidden("游客聊天已被管理员关闭"));
            }
            if (!rateLimiter.tryAcquire("guest-create:" + ip, 5)) {
                return Mono.error(ChatException.tooManyRequests("创建游客身份过于频繁，请稍后再试"));
            }
            var nickname = normalizeNickname(rawNickname, general.getGuestNameMaxLength());
            return ensureNicknameAvailable(nickname)
                .then(createGuest(nickname, ip, userAgent))
                .flatMap(guest -> issueSession(guest, exchange));
        });
    }

    /** Renames the current guest session, if the cookie is valid. */
    public Mono<GuestSession> renameSession(String rawNickname, ServerWebExchange exchange) {
        return currentGuest(exchange)
            .switchIfEmpty(Mono.error(ChatException.unauthorized("游客会话已失效，请重新输入昵称")))
            .flatMap(guest -> settingsService.general().flatMap(general -> {
                var nickname = normalizeNickname(rawNickname, general.getGuestNameMaxLength());
                return ensureNicknameAvailable(nickname).then(Mono.defer(() -> {
                    var spec = guest.getSpec();
                    spec.setNickname(nickname);
                    spec.setLastSeenAt(Instant.now());
                    return client.update(guest).flatMap(updated -> issueSession(updated, exchange));
                }));
            }));
    }

    /** Resolves the guest session referenced by the request cookie, if any. */
    public Mono<ChatGuest> currentGuest(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(Constants.GUEST_COOKIE);
        if (cookie == null || !StringUtils.hasText(cookie.getValue())) {
            return Mono.empty();
        }
        return parseAndVerify(cookie.getValue())
            .flatMap(guestName -> client.fetch(ChatGuest.class, guestName))
            .doOnNext(guest -> {
                var spec = guest.getSpec();
                var now = Instant.now();
                var lastSeen = spec.getLastSeenAt();
                if (lastSeen != null && lastSeen.isAfter(now.minusSeconds(300))) {
                    return;
                }
                spec.setLastSeenAt(now);
                client.update(guest).subscribe(ignored -> {
                }, error -> log.debug("Failed to update guest last seen time", error));
            });
    }

    /** Deletes guest sessions that were inactive for a long time. */
    public Mono<Long> cleanupInactive(Duration maxIdle) {
        var threshold = Instant.now().minus(maxIdle);
        return client.listAll(ChatGuest.class, run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.asc("metadata.creationTimestamp")))
            .filter(guest -> guest.getSpec().getLastSeenAt() == null
                || guest.getSpec().getLastSeenAt().isBefore(threshold))
            .flatMap(client::delete)
            .count();
    }

    /** Returns a Set-Cookie header value for the given guest. */
    public Mono<String> cookieHeader(ChatGuest guest, ServerWebExchange exchange) {
        return sign(guest.getMetadata().getName())
            .map(signature -> buildCookie(guest.getMetadata().getName() + "." + signature,
                exchange).toString());
    }

    private Mono<ChatGuest> createGuest(String nickname, String ip, String userAgent) {
        var guest = new ChatGuest();
        var metadata = new Metadata();
        metadata.setGenerateName("chat-guest-");
        guest.setMetadata(metadata);
        var spec = new ChatGuest.Spec();
        spec.setNickname(nickname);
        spec.setIpHash(hash(ip));
        spec.setUserAgent(truncate(userAgent, 300));
        spec.setCreatedAt(Instant.now());
        spec.setLastSeenAt(Instant.now());
        spec.setMessageCount(0L);
        guest.setSpec(spec);
        return client.create(guest);
    }

    private Mono<GuestSession> issueSession(ChatGuest guest, ServerWebExchange exchange) {
        return sign(guest.getMetadata().getName()).map(signature -> {
            var value = guest.getMetadata().getName() + "." + signature;
            var cookie = buildCookie(value, exchange);
            return new GuestSession(guest.getMetadata().getName(), guest.getSpec().getNickname(),
                cookie.toString());
        });
    }

    private ResponseCookie buildCookie(String value, ServerWebExchange exchange) {
        var secure = isSecure(exchange);
        return ResponseCookie.from(Constants.GUEST_COOKIE, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(Constants.GUEST_COOKIE_TTL)
            .build();
    }

    private boolean isSecure(ServerWebExchange exchange) {
        var uri = exchange.getRequest().getURI();
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        var forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return forwarded != null && forwarded.toLowerCase().contains("https");
    }

    private Mono<String> parseAndVerify(String value) {
        var index = value.lastIndexOf('.');
        if (index <= 0 || index == value.length() - 1) {
            return Mono.empty();
        }
        var guestName = value.substring(0, index);
        var signature = value.substring(index + 1);
        return sign(guestName)
            .filter(expected -> constantTimeEquals(expected, signature))
            .map(ignored -> guestName);
    }

    private Mono<byte[]> signingKey() {
        var cached = signingKey.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return client.fetch(Secret.class, Constants.SECRET_NAME)
            .flatMap(this::readSecret)
            .switchIfEmpty(Mono.defer(this::createSecret))
            .doOnNext(key -> signingKey.set(key));
    }

    private Mono<byte[]> readSecret(Secret secret) {
        return Mono.fromCallable(() -> {
            var data = secret.getData();
            var value = data == null ? null : data.get(Constants.SECRET_KEY_GUEST_SIGNING);
            return value == null || value.length < 16 ? null : value;
        }).subscribeOn(Schedulers.boundedElastic())
            .switchIfEmpty(Mono.defer(this::createSecret));
    }

    private Mono<byte[]> createSecret() {
        return Mono.fromCallable(() -> {
            var key = new byte[32];
            RANDOM.nextBytes(key);
            var secret = new Secret();
            var metadata = new Metadata();
            metadata.setName(Constants.SECRET_NAME);
            secret.setMetadata(metadata);
            secret.setType(Secret.SECRET_TYPE_OPAQUE);
            secret.setData(Map.of(Constants.SECRET_KEY_GUEST_SIGNING, key));
            return secret;
        }).subscribeOn(Schedulers.boundedElastic())
            .flatMap(secret -> client.create(secret)
                .onErrorResume(error -> client.fetch(Secret.class, Constants.SECRET_NAME))
                .flatMap(this::readExistingSecret));
    }

    private Mono<byte[]> readExistingSecret(Secret secret) {
        var data = secret.getData();
        var value = data == null ? null : data.get(Constants.SECRET_KEY_GUEST_SIGNING);
        if (value == null || value.length < 16) {
            return Mono.error(ChatException.conflict("无法初始化游客会话签名密钥"));
        }
        return Mono.just(value);
    }

    private Mono<String> sign(String value) {
        return signingKey().map(key -> {
            try {
                var mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
                var bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to sign guest session", e);
            }
        });
    }

    private Mono<Void> ensureNicknameAvailable(String nickname) {
        var lowered = nickname.toLowerCase();
        if (RESERVED_NAMES.contains(lowered)) {
            return Mono.error(ChatException.badRequest("这个昵称是保留名称，请换一个"));
        }
        // Halo usernames must not be impersonated
        return client.fetch(User.class, lowered)
            .flatMap(user -> Mono.<Void>error(ChatException.badRequest("该昵称已被注册用户使用，请换一个")))
            .switchIfEmpty(Mono.empty());
    }

    private String normalizeNickname(String raw, Integer maxLength) {
        var nickname = raw == null ? "" : raw.strip();
        var limit = maxLength == null ? 20 : maxLength;
        if (nickname.isEmpty()) {
            throw ChatException.badRequest("请输入昵称");
        }
        if (nickname.length() > limit) {
            throw ChatException.badRequest("昵称不能超过 " + limit + " 个字符");
        }
        if (!nickname.matches("^[\\p{L}\\p{N}_\\-.· ]+$")) {
            throw ChatException.badRequest("昵称只能包含文字、数字、空格以及 _ - . ·");
        }
        return nickname;
    }

    private String clientIp(ServerWebExchange exchange) {
        var forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].strip();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
            ? "unknown" : remote.getAddress().getHostAddress();
    }

    private String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Result of creating or renaming a guest session. */
    public record GuestSession(String guestId, String nickname, String setCookie) {
    }
}
