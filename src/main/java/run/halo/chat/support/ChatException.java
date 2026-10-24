package run.halo.chat.support;

import org.springframework.http.HttpStatus;

/**
 * Business exception that maps to a JSON problem response.
 *
 * @author halo-chat
 * @since 1.0.0
 */
public class ChatException extends RuntimeException {

    private final HttpStatus status;

    public ChatException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ChatException badRequest(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, message);
    }

    public static ChatException unauthorized(String message) {
        return new ChatException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ChatException forbidden(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, message);
    }

    public static ChatException notFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, message);
    }

    public static ChatException conflict(String message) {
        return new ChatException(HttpStatus.CONFLICT, message);
    }

    public static ChatException tooManyRequests(String message) {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static ChatException payloadTooLarge(String message) {
        return new ChatException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    public static ChatException gone(String message) {
        return new ChatException(HttpStatus.GONE, message);
    }
}
