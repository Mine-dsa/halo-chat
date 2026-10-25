package cc.minedsa.halochat.endpoint;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import cc.minedsa.halochat.support.Responses;

/**
 * Serves the standalone chat page for visitors and signed-in users.
 *
 * <p>The path is not part of Halo's RBAC protected {@code /apis/**} namespace,
 * so guests can open the page. The page itself only talks to the public API
 * which enforces the channel policies.</p>
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class ChatPageRouter {

    private static final String PAGE_PATH = "static/web/index.html";
    private static final String PAGE_URL = "/plugins/halo-chat/";
    private final AtomicReference<String> cachedPage = new AtomicReference<>();

    @Bean
    public RouterFunction<ServerResponse> chatPageRoutes() {
        return RouterFunctions.route()
            .GET("/plugins/halo-chat", request -> page())
            .GET("/plugins/halo-chat/", request -> page())
            .GET("/plugins/halo-chat/index.html", request -> page())
            .GET("/plugins/halo-chat/health",
                request -> Responses.json(Map.of("ok", true, "page", PAGE_URL)))
            .build();
    }

    private Mono<ServerResponse> page() {
        var cached = cachedPage.get();
        if (cached != null) {
            return html(cached);
        }
        return Mono.fromCallable(() -> {
                var resource = new ClassPathResource(PAGE_PATH,
                    ChatPageRouter.class.getClassLoader());
                if (!resource.exists()) {
                    log.warn("Chat page resource {} does not exist in the plugin classloader",
                        PAGE_PATH);
                    return null;
                }
                try (var input = resource.getInputStream()) {
                    var content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    log.info("Serving chat page ({} bytes) from {}", content.length(), PAGE_PATH);
                    return content;
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(content -> {
                if (content == null) {
                    return ServerResponse.status(503)
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue("""
                            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                            <title>聊天</title></head><body>
                            <h1>聊天页面尚未构建</h1>
                            <p>请运行 <code>./gradlew build</code> 重新打包插件，或联系管理员。</p>
                            </body></html>
                            """);
                }
                cachedPage.set(content);
                return html(content);
            });
    }

    private Mono<ServerResponse> html(String content) {
        return ServerResponse.ok()
            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noCache())
            .bodyValue(content);
    }
}
