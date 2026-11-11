package au.edu.adelaide.assignment1.exception;

/**
 * Exception thrown when a graceful shutdown is already in progress.
 */
public class ShutdownInProgressException extends RuntimeException {

    /**
     * Constructs a new ShutdownInProgressException with a default message.
     */
    public ShutdownInProgressException() {
        super("Graceful shutdown is already in progress.");
    }
}
