package au.edu.adelaide.assignment1.dto;

import java.time.Instant;

/**
 * Represents an error response in the application.
 * @param timestamp the timestamp when the error occurred.
 * @param status the HTTP status code of the error.
 * @param error the type of error.
 * @param message the detailed error message.
 * @param path the request path that caused the error.
 */
public record ErrorResponse (
    Instant timestamp,
    int status,
    String error,
    String message,
    String path
) {}
