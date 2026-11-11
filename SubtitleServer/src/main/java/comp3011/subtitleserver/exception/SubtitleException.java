/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011.subtitleserver.exception;

import org.springframework.http.HttpStatus;

/**
 * Carries only a safe public error message, never a provider response or credential.
 */
public class SubtitleException extends RuntimeException {
    private final HttpStatus status;

    /**
     * @param status  response status
     * @param message safe explanation for the player
     */
    public SubtitleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * @return response status
     */
    public HttpStatus getStatus() {
        return status;
    }
}
