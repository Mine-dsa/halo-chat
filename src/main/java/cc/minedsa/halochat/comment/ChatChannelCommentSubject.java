package cc.minedsa.halochat.comment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.comment.CommentSubject;
import run.halo.app.extension.Ref;
import run.halo.app.extension.ReactiveExtensionClient;
import cc.minedsa.halochat.Constants;
import cc.minedsa.halochat.extension.ChatChannel;

/**
 * Makes chat channels valid comment subjects so that the official Halo comment
 * widget can be used as an alternative message composer.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class ChatChannelCommentSubject implements CommentSubject<ChatChannel> {

    private final ReactiveExtensionClient client;

    @Override
    public Mono<ChatChannel> get(String name) {
        return client.fetch(ChatChannel.class, name);
    }

    @Override
    public boolean supports(Ref ref) {
        return ref != null
            && Constants.EXTENSION_GROUP.equals(ref.getGroup())
            && "ChatChannel".equals(ref.getKind());
    }

    @Override
    public Mono<SubjectDisplay> getSubjectDisplay(String name) {
        return client.fetch(ChatChannel.class, name)
            .map(channel -> new SubjectDisplay(
                channel.getSpec().getDisplayName(),
                "/plugins/halo-chat/?channel=" + channel.getMetadata().getName(),
                "聊天频道"));
    }
}
