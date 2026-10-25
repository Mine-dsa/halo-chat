package cc.minedsa.halochat.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import cc.minedsa.halochat.Constants;
import cc.minedsa.halochat.extension.ChatChannel;
import cc.minedsa.halochat.service.ChannelService;
import cc.minedsa.halochat.service.Identity;
import cc.minedsa.halochat.service.MessageService;

/**
 * Mirrors Halo comments created through the official comment widget into chat
 * messages of the matching channel, so both composer modes share one message
 * stream.
 *
 * <p>Channel posting policies are enforced here as well: comments created
 * directly against the core comment API do not pass through the chat API, so
 * unauthorized comments are removed instead of being mirrored.</p>
 *
 * <p>On plugin start the controller synchronizes all existing comments, which
 * also backfills messages created while the plugin was disabled.</p>
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentMirrorReconciler implements Reconciler<Reconciler.Request> {

    private final ReactiveExtensionClient client;
    private final MessageService messageService;
    private final ChannelService channelService;

    @Override
    public Result reconcile(Request request) {
        try {
            client.fetch(Comment.class, request.name())
                .flatMap(comment -> mirror(comment).thenReturn(true))
                .switchIfEmpty(Mono.defer(() ->
                    messageService.deleteFromComment(request.name()).thenReturn(false)))
                .onErrorResume(error -> {
                    log.warn("Failed to mirror chat comment {}", request.name(), error);
                    return Mono.just(false);
                })
                .block();
        } catch (Exception e) {
            log.warn("Failed to reconcile chat comment {}", request.name(), e);
        }
        return Result.doNotRetry();
    }

    private static final String ANNO_REJECTED = "chat.halo.run/comment-rejected";

    private Mono<Void> mirror(Comment comment) {
        var subjectRef = comment.getSpec().getSubjectRef();
        if (subjectRef == null
            || !Constants.EXTENSION_GROUP.equals(subjectRef.getGroup())
            || !"ChatChannel".equals(subjectRef.getKind())
            || !StringUtils.hasText(subjectRef.getName())) {
            return Mono.empty();
        }
        if (Boolean.TRUE.equals(comment.getSpec().getHidden())) {
            return messageService.deleteFromComment(comment.getMetadata().getName()).then();
        }
        return client.fetch(ChatChannel.class, subjectRef.getName())
            .flatMap(channel -> {
                var identity = identityOf(comment);
                return channelService.canPost(identity, channel)
                    .flatMap(allowed -> {
                        if (!allowed) {
                            log.info("Rejecting chat comment {} from {}: no post permission",
                                comment.getMetadata().getName(), identity.name());
                            return reject(comment);
                        }
                        return messageService.upsertFromComment(comment, channel).then();
                    });
            })
            .then();
    }

    /**
     * A comment submitted directly to the Halo comment API bypasses the chat
     * API. When the channel policy denies the author, hide the comment instead
     * of keeping it public. The marker annotation makes this idempotent: the
     * update triggers another reconcile event and must not loop.
     */
    private Mono<Void> reject(Comment comment) {
        var metadata = comment.getMetadata();
        var annotations = metadata.getAnnotations();
        if (annotations == null) {
            annotations = new java.util.HashMap<>();
            metadata.setAnnotations(annotations);
        }
        if ("true".equals(annotations.get(ANNO_REJECTED))) {
            return Mono.empty();
        }
        annotations.put(ANNO_REJECTED, "true");
        comment.getSpec().setHidden(true);
        comment.getSpec().setApproved(false);
        return client.update(comment)
            .onErrorResume(error -> {
                log.debug("Failed to hide unauthorized chat comment {}",
                    metadata.getName(), error);
                return Mono.empty();
            })
            .then();
    }

    private Identity identityOf(Comment comment) {
        var owner = comment.getSpec().getOwner();
        if (owner == null) {
            return Identity.anonymous();
        }
        if ("User".equalsIgnoreCase(owner.getKind())) {
            var name = StringUtils.hasText(owner.getName()) ? owner.getName() : "";
            return new Identity(Identity.Type.USER, name,
                owner.getDisplayName() == null || owner.getDisplayName().isBlank()
                    ? "Halo 用户" : owner.getDisplayName(), null, false);
        }
        return new Identity(Identity.Type.GUEST,
            owner.getName() == null ? "email-guest" : owner.getName(),
            owner.getDisplayName() == null ? "游客" : owner.getDisplayName(), null, false);
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new Comment()).build();
    }
}
