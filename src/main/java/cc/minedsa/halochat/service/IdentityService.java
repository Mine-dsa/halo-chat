package cc.minedsa.halochat.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.AnonymousUserConst;

/**
 * Resolves the current chat identity from the Halo security context and the
 * guest session cookie.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityService {

    private final ReactiveExtensionClient client;
    private final GuestService guestService;
    private final PermissionService permissionService;

    public Mono<Identity> resolve(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
            .map(org.springframework.security.core.context.SecurityContext::getAuthentication)
            .filter(authentication -> authentication != null
                && authentication.isAuthenticated()
                && !AnonymousUserConst.isAnonymousUser(authentication.getName()))
            .flatMap(this::resolveUser)
            .switchIfEmpty(Mono.defer(() -> resolveGuest(exchange)));
    }

    private Mono<Identity> resolveUser(org.springframework.security.core.Authentication authentication) {
        var username = authentication.getName();
        var isSuperAdmin = authentication.getAuthorities().stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .anyMatch(authority -> "ROLE_super-role".equals(authority)
                || "super-role".equals(authority));
        return client.fetch(User.class, username)
            .map(user -> {
                var spec = user.getSpec();
                var displayName = StringUtils.hasText(spec.getDisplayName())
                    ? spec.getDisplayName() : username;
                return new Identity(Identity.Type.USER, username, displayName,
                    spec.getAvatar(), isSuperAdmin);
            })
            .defaultIfEmpty(new Identity(Identity.Type.USER, username, username, null,
                isSuperAdmin))
            .flatMap(identity -> isSuperAdmin
                ? Mono.just(identity)
                : permissionService.canManage(identity).map(identity::withCanManage));
    }

    private Mono<Identity> resolveGuest(ServerWebExchange exchange) {
        return guestService.currentGuest(exchange)
            .map(guest -> new Identity(Identity.Type.GUEST,
                guest.getMetadata().getName(),
                guest.getSpec().getNickname(),
                null, false))
            .defaultIfEmpty(Identity.anonymous());
    }

    /** Produces a compact sender snapshot for a message. */
    public Mono<ChatMessageSender> senderOf(Identity identity) {
        if (identity == null) {
            return Mono.just(ChatMessageSender.system());
        }
        if (identity.isUser()) {
            return Mono.just(new ChatMessageSender(
                cc.minedsa.halochat.extension.ChatMessage.SenderType.USER,
                identity.name(), identity.displayName(), identity.avatar()));
        }
        if (identity.isGuest()) {
            return Mono.just(new ChatMessageSender(
                cc.minedsa.halochat.extension.ChatMessage.SenderType.GUEST,
                identity.name(), identity.displayName(), null));
        }
        return Mono.just(ChatMessageSender.system());
    }

    /**
     * Small sender value object used while building messages.
     */
    public record ChatMessageSender(cc.minedsa.halochat.extension.ChatMessage.SenderType type,
                                    String name, String displayName, String avatar) {
        public static ChatMessageSender system() {
            return new ChatMessageSender(
                cc.minedsa.halochat.extension.ChatMessage.SenderType.SYSTEM, "system", "系统", null);
        }
    }
}
