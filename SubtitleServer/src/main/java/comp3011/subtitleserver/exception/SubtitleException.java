/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011.subtitleserver.exception;

import org.springframework.http.HttpStatus;

/**
 * Carries only a safe public error message, never a provider response or credential.
 */
public class SubtitleException extends RuntimeException {
    private final HttpStatus status;
    private final boolean retryable;

    /**
     * @param status  response status
     * @param message safe explanation for the player
     */
    public SubtitleException(HttpStatus status, String message) {
        this(status, message, false);
    }

    public SubtitleException(HttpStatus status, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * @return response status
     */
    public HttpStatus getStatus() {
        return status;
    }
}
