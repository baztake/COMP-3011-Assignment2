/* Adapted from Assignment 1 server with assistance from OpenAI Codex. */
package comp3011.subtitleserver.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Produces safe JSON errors without forwarding provider bodies or stack traces. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /**
     * @param error safe application failure
     * @param request current HTTP request
     * @return structured error with the intended status
     */
    @ExceptionHandler(SubtitleException.class)
    public ResponseEntity<ApiError> subtitleFailure(SubtitleException error, HttpServletRequest request) {
        return response(error.getStatus().value(), error.getMessage(), request);
    }

    /**
     * @param error request failure
     * @param request current HTTP request
     * @return safe error preserving Spring's request-validation status codes
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> requestFailure(Exception error, HttpServletRequest request) {
        if (error instanceof MaxUploadSizeExceededException) {
            return response(413, "Audio chunks must not exceed 24 MiB", request);
        }
        if (error instanceof ErrorResponse frameworkError) {
            int status = frameworkError.getStatusCode().value();
            HttpStatus known = HttpStatus.resolve(status);
            return response(status, known == null ? "Invalid request" : known.getReasonPhrase(), request);
        }
        // Exception messages can contain upstream bodies. Log types only, keeping credentials out of logs.
        log.error("Unexpected subtitle request failure: {} (cause: {})", error.getClass().getName(),
            error.getCause() == null ? "none" : error.getCause().getClass().getName());
        return response(500, "An unexpected server error occurred", request);
    }

    private ResponseEntity<ApiError> response(int status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(status, message, request.getRequestURI()));
    }

    /**
     * @param status HTTP status
     * @param message safe public explanation
     * @param path request path
     */
    public record ApiError(int status, String message, String path) {}
}
