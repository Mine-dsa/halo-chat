package cc.minedsa.halochat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Reads the plugin settings. Every group is fetched lazily so settings changes
 * take effect immediately without restarting the plugin.
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class SettingsService {

    private final ReactiveSettingFetcher settingFetcher;

    public Mono<ChatSettings.General> general() {
        return settingFetcher.fetch("general", ChatSettings.General.class)
            .map(SettingsService::normalize)
            .defaultIfEmpty(normalize(new ChatSettings.General()));
    }

    public Mono<ChatSettings.Files> files() {
        return settingFetcher.fetch("files", ChatSettings.Files.class)
            .map(SettingsService::normalize)
            .defaultIfEmpty(normalize(new ChatSettings.Files()));
    }

    public Mono<ChatSettings.Composer> composer() {
        return settingFetcher.fetch("composer", ChatSettings.Composer.class)
            .defaultIfEmpty(normalize(new ChatSettings.Composer()));
    }

    public Mono<ChatSettings> get() {
        return Mono.zip(general(), files(), composer())
            .map(tuple -> {
                var settings = new ChatSettings();
                settings.setGeneral(tuple.getT1());
                settings.setFiles(tuple.getT2());
                settings.setComposer(tuple.getT3());
                return settings;
            });
    }

    private static ChatSettings.General normalize(ChatSettings.General general) {
        var result = general == null ? new ChatSettings.General() : general;
        result.setGuestNameMaxLength(clamp(result.getGuestNameMaxLength(), 1, 60, 20));
        result.setMessageMaxLength(clamp(result.getMessageMaxLength(), 1, 20000, 2000));
        result.setGuestRateLimit(label(result.getGuestRateLimit(), 20));
        result.setUserRateLimit(label(result.getUserRateLimit(), 120));
        if (result.getGuestEnabled() == null) {
            result.setGuestEnabled(true);
        }
        if (result.getGuestUploadEnabled() == null) {
            result.setGuestUploadEnabled(false);
        }
        return result;
    }

    private static ChatSettings.Files normalize(ChatSettings.Files files) {
        var result = files == null ? new ChatSettings.Files() : files;
        result.setMaxFileSizeMb(normalizeSize(result.getMaxFileSizeMb(), 100L));
        result.setMaxTotalSizeMb(normalizeSize(result.getMaxTotalSizeMb(), 1024L));
        result.setExpireDays(label(result.getExpireDays(), 0));
        return result;
    }

    private static ChatSettings.Composer normalize(ChatSettings.Composer composer) {
        var result = composer == null ? new ChatSettings.Composer() : composer;
        var mode = result.getMode() == null ? "BUILTIN" : result.getMode();
        result.setMode("HALO_COMMENT".equalsIgnoreCase(mode) ? "HALO_COMMENT" : "BUILTIN");
        return result;
    }

    private static Long normalizeSize(Long value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(0L, value);
    }

    private static int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int label(Integer value, int defaultValue) {
        return value == null ? defaultValue : Math.max(0, value);
    }
}
