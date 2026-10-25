package cc.minedsa.halochat;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import cc.minedsa.halochat.extension.ChatChannel;
import cc.minedsa.halochat.extension.ChatFile;
import cc.minedsa.halochat.extension.ChatGuest;
import cc.minedsa.halochat.extension.ChatMessage;
import cc.minedsa.halochat.service.ChannelService;
import cc.minedsa.halochat.service.FileService;
import cc.minedsa.halochat.service.GuestService;

/**
 * Plugin lifecycle: registers the custom resources and bootstraps the public
 * channel, the guest signing key and the storage accounting.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Slf4j
@Component
@EnableScheduling
public class ChatPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final ChannelService channelService;
    private final GuestService guestService;
    private final FileService fileService;

    public ChatPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                      ChannelService channelService, GuestService guestService,
                      FileService fileService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.channelService = channelService;
        this.guestService = guestService;
        this.fileService = fileService;
    }

    @Override
    public void start() {
        registerSchemes();
        try {
            channelService.ensurePublicChannel().block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.error("Failed to initialize the public chat channel", e);
        }
        try {
            guestService.ensureSecret().block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.error("Failed to initialize the guest signing key", e);
        }
        try {
            fileService.initializeQuota().block(Duration.ofSeconds(60));
        } catch (Exception e) {
            log.error("Failed to initialize chat storage accounting", e);
        }
        log.info("halo-chat started");
    }

    @Override
    public void stop() {
        unregisterSchemes();
        log.info("halo-chat stopped");
    }

    private void registerSchemes() {
        schemeManager.register(ChatChannel.class);
        schemeManager.register(ChatMessage.class, specs -> specs.add(
            IndexSpecs.<ChatMessage, String>single("spec.channel", String.class)
                .indexFunc(message -> message.getSpec().getChannel())));
        schemeManager.register(ChatFile.class, specs -> {
            specs.add(IndexSpecs.<ChatFile, String>single("spec.channel", String.class)
                .indexFunc(file -> file.getSpec().getChannel()));
            specs.add(IndexSpecs.<ChatFile, Instant>single("spec.expiresAt", Instant.class)
                .indexFunc(file -> file.getSpec().getExpiresAt()));
        });
        schemeManager.register(ChatGuest.class);
    }

    private void unregisterSchemes() {
        for (var type : java.util.List.of(ChatChannel.class, ChatMessage.class, ChatFile.class,
            ChatGuest.class)) {
            try {
                schemeManager.unregister(Scheme.buildFromType(type));
            } catch (Exception e) {
                log.debug("Failed to unregister scheme for {}", type.getName(), e);
            }
        }
    }
}
