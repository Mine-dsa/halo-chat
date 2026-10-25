package cc.minedsa.halochat.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Settings read from the plugin Setting resource.
 *
 * <p>All limits use {@code 0} to mean "unlimited".</p>
 *
 * @author mine-dsa
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
public class ChatSettings {

    private General general = new General();
    private Files files = new Files();
    private Composer composer = new Composer();

    @Data
    @NoArgsConstructor
    public static class General {
        /** Whether visitors may chat at all. */
        private Boolean guestEnabled = true;
        /** Whether visitors may upload files (text is always allowed when guestEnabled). */
        private Boolean guestUploadEnabled = false;
        private Integer guestNameMaxLength = 20;
        private Integer messageMaxLength = 2000;
        /** Messages per minute for the same guest session / IP. 0 = unlimited. */
        private Integer guestRateLimit = 20;
        /** Messages per minute for the same signed-in user. 0 = unlimited. */
        private Integer userRateLimit = 120;
    }

    @Data
    @NoArgsConstructor
    public static class Files {
        /** Max size of a single file in MB. 0 = unlimited. */
        private Long maxFileSizeMb = 100L;
        /** Max total size of all stored chat files in MB. 0 = unlimited. */
        private Long maxTotalSizeMb = 1024L;
        /** Days after which a file expires. 0 = never. */
        private Integer expireDays = 0;

        public long maxFileSizeBytes() {
            var value = maxFileSizeMb == null ? 0 : Math.max(0, maxFileSizeMb);
            return value * 1024L * 1024L;
        }

        public long maxTotalSizeBytes() {
            var value = maxTotalSizeMb == null ? 0 : Math.max(0, maxTotalSizeMb);
            return value * 1024L * 1024L;
        }
    }

    @Data
    @NoArgsConstructor
    public static class Composer {
        /** BUILTIN or HALO_COMMENT. */
        private String mode = "BUILTIN";
    }

    public General getGeneral() {
        return general == null ? new General() : general;
    }

    public Files getFiles() {
        return files == null ? new Files() : files;
    }

    public Composer getComposer() {
        return composer == null ? new Composer() : composer;
    }

    /** Normalizes user supplied values into a safe range. */
    public static long unlimitedOrZero(Long value) {
        return value == null || value < 0 ? 0 : value;
    }

    public static int unlimitedOrZero(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }
}
