package run.halo.chat.support;

import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Helpers shared by the functional endpoints.
 *
 * @author halo-chat
 * @since 1.0.0
 */
@lombok.extern.slf4j.Slf4j
public final class Responses {

    private Responses() {
    }

    /**
     * Wraps a handler body so that {@link ChatException}s become consistent JSON
     * problem responses instead of the generic HTML error page.
     */
    public static Mono<ServerResponse> guarded(Supplier<Mono<ServerResponse>> body) {
        return Mono.defer(body)
            .onErrorResume(ChatException.class, Responses::problem)
            .onErrorResume(error -> {
                log.error("Chat API request failed", error);
                var detail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");
                detail.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(detail);
            });
    }

    public static Mono<ServerResponse> problem(ChatException exception) {
        var detail = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        detail.setTitle(exception.getStatus().getReasonPhrase());
        return ServerResponse.status(exception.getStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(detail);
    }

    public static Mono<ServerResponse> json(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    public static Mono<ServerResponse> created(Object body) {
        return ServerResponse.status(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }
}
