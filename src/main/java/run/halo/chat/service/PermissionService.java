package run.halo.chat.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Role;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.MetadataUtil;
import run.halo.chat.Constants;

/**
 * Resolves whether a signed-in user holds the plugin's UI manage permission.
 *
 * <p>Halo enforces actual API access with RBAC; this helper only drives UI
 * affordances (menu items and buttons).</p>
 *
 * @author halo-chat
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionService {

    private static final Pattern JSON_STRING = Pattern.compile("\"([^\"]+)\"");
    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final RoleService roleService;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Mono<Boolean> canManage(Identity identity) {
        if (identity == null || !identity.isUser()) {
            return Mono.just(false);
        }
        if (identity.canManage()) {
            return Mono.just(true);
        }
        var cached = cache.get(identity.name());
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return Mono.just(cached.value());
        }
        return roleService.getRolesByUsername(identity.name())
            .collectList()
            .flatMapMany(roles -> roleService.listPermissions(new HashSet<>(roles)))
            .flatMapIterable(this::uiPermissions)
            .any(permission -> "*".equals(permission)
                || Constants.UI_PERMISSION_MANAGE.equals(permission))
            .defaultIfEmpty(false)
            .doOnNext(value -> cache.put(identity.name(),
                new CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MILLIS)))
            .onErrorResume(error -> {
                log.debug("Failed to resolve plugin permissions for {}", identity.name(), error);
                return Mono.just(false);
            });
    }

    private List<String> uiPermissions(Role role) {
        var annotations = MetadataUtil.nullSafeAnnotations(role);
        var value = annotations.get(Role.UI_PERMISSIONS_AGGREGATED_ANNO);
        if (value == null) {
            value = annotations.get(Role.UI_PERMISSIONS_ANNO);
        }
        if (value == null) {
            return List.of();
        }
        var permissions = new ArrayList<String>();
        var matcher = JSON_STRING.matcher(value);
        while (matcher.find()) {
            permissions.add(matcher.group(1));
        }
        return permissions;
    }

    private record CacheEntry(boolean value, long expiresAt) {
    }
}
