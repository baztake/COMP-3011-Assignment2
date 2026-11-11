package au.edu.adelaide.assignment1.exception;

import au.edu.adelaide.assignment1.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;

/**
 * Global exception handler for the application.
 * This class handles various exceptions and returns appropriate HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles ShutdownInProgressException and returns a 409 Conflict response.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(ShutdownInProgressException.class)
    public ResponseEntity<ErrorResponse> handleShutdownInProgress(
        ShutdownInProgressException ex,
        HttpServletRequest request
    ) {
        log.error("Shutdown In Progress Exception: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
            Instant.now(),
            409,
            "Conflict",
            "Graceful shutdown is already in progress.",
            request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handles MissingServletRequestPartException and returns a 400 Bad Request response.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestPart(
        MissingServletRequestPartException ex,
        HttpServletRequest request
    ) {
        log.error("Missing request part: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
            Instant.now(),
            400,
            "Bad Request",
            "Required request part is missing.",
            request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handles MethodArgumentTypeMismatchException and returns a 400 Bad Request response.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
        MethodArgumentTypeMismatchException ex,
        HttpServletRequest request
    ) {
        log.error("Method argument type mismatch: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
            Instant.now(),
            400,
            "Bad Request",
            "Method argument type mismatch.",
            request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handles HttpMediaTypeNotSupportedException and returns a 415 Unsupported Media Type response.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupported(
        HttpMediaTypeNotSupportedException ex,
        HttpServletRequest request
    ) {
        log.error("HTTP media type not supported: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
            Instant.now(),
            415,
            "Unsupported Media Type",
            "The media type of the request is not supported.",
            request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    /**
     * Handles HttpRequestMethodNotSupportedException and returns a 405 Method Not Allowed response.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupported(
        HttpRequestMethodNotSupportedException ex,
        HttpServletRequest request
    ) {
        log.error("HTTP request method not supported: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
            Instant.now(),
            405,
            "Method Not Allowed",
            "The HTTP method used is not supported for this endpoint.",
            request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    /**
     * Handles Exception and returns a 500 Internal Server Error response.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(value = Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
        Exception ex,
        HttpServletRequest request
    ) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        ErrorResponse error = new ErrorResponse(
            Instant.now(),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
            "An unexpected error occurred.",
            request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}