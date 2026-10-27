/*
 * Concurrency documentation written with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Processes frames concurrently and returns results in submission order.
 * A FIFO queue holds futures, so later frames cannot overtake its head.
 * Each executor thread owns a worker with separate processor instances.
 * Synchronised methods protect queue access and coordinate shutdown.
 * The factory must supply separate, initialised workers and processor instances.
 */
public class FrameProcessingPool implements AutoCloseable {

    private final ExecutorService executor;
    private final int maxQueuedFrames;
    private final ThreadLocal<FrameProcessingWorker> workers;

    // Queue positions represent submission order, regardless of when the corresponding processing tasks finish.
    private final Queue<Future<PreparedFrame>> results = new ArrayDeque<>();

    // Only used in synchronised methods.
    private boolean closed;

    /**
     * Creates a fixed-size pool with worker-private processing state.
     * Initialises pool with a limit on accepted, non-displayed frames.
     *
     * @param workerCount     number of processing threads
     * @param maxQueuedFrames maximum number of frames to be processing/processed waiting for display
     * @param workerFactory   supplies fresh workers with initialised processors
     * @throws IllegalArgumentException if workerCount is not positive
     * @throws NullPointerException     if workerFactory is null
     */
    public FrameProcessingPool(int workerCount, int maxQueuedFrames, Supplier<FrameProcessingWorker> workerFactory) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        if (maxQueuedFrames <= 0) {
            throw new IllegalArgumentException("Max queued frames must be positive");
        }
        this.maxQueuedFrames = maxQueuedFrames;
        this.workers = ThreadLocal.withInitial(workerFactory);
        this.executor = Executors.newFixedThreadPool(workerCount);
    }

    /**
     * Waits for queue space and submits a copied frame.
     * Call from the decoder thread because a full queue can block submission.
     * Successful submission transfers ownership to the task, which closes the input.
     * Waiting, interrupted, or rejected submissions leave ownership with the caller.
     *
     * @param frame      copied input frame to process
     * @param info       frame number and media timestamp
     * @param generation playback generation associated with the frame
     * @throws InterruptedException       if interrupted while waiting for queue space
     * @throws RejectedExecutionException if the pool is closed
     */
    public synchronized void submit(Frame frame, InfoFrame info, long generation) throws InterruptedException {
        // This method is synchronised, so we use a while loop here to ensure that if another thread wakes and takes the
        // space first, this thread will go back to sleep
        while (!closed && results.size() >= maxQueuedFrames) {
            // If the size of the queue is greater than the maximum allowed, wait until space is available.
            wait();
        }

        // If the pool is closed, throw an exception.
        if (closed) {
            throw new RejectedExecutionException("Frame processing pool is closed");
        }

        Future<PreparedFrame> result = executor.submit(() -> {
            try {
                return workers.get().process(frame, info, generation);
            } finally {
                frame.close();
            }
        });

        // Completion does not free space; the future stays queued until polled.
        results.add(result);
    }

    /**
     * Retrieves the oldest result when ready, freeing one queue slot.
     * Does not wait for processing, but may wait to acquire the pool monitor.
     * The model must poll only when its pending presentation slot is empty.
     *
     * @return oldest result, or null if the queue is empty or its head is unfinished
     * @throws ExecutionException   if worker creation or processing failed
     * @throws InterruptedException if result retrieval is interrupted
     */
    public synchronized PreparedFrame pollReady() throws ExecutionException, InterruptedException {
        // Inspect only the oldest submission.
        // Searching for any completed future would allow later frames to overtake earlier ones.
        Future<PreparedFrame> next = results.peek();

        if (next == null || !next.isDone()) {
            // Leave the head in place until its processing has finished.
            return null;
        }

        // The head has finished, so retrieving it cannot wait for unfinished processing.
        // Future.get() also safely publishes the task's result and reports any processing failure.
        results.remove();

        // Wake any sleeping threads that may be waiting for queue space.
        notifyAll();
        return next.get();
    }

    /**
     * Rejects new work and wakes submitters waiting for queue space.
     * Accepted tasks finish normally and their results remain available for polling.
     * Does not cancel tasks or wait for termination.
     */
    @Override
    public synchronized void close() {
        closed = true;
        executor.shutdown();
        notifyAll();
    }
}