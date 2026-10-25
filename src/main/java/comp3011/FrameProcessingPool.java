/*
 * Concurrency documentation written with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Processes frames concurrently and returns results in submission order.
 * A FIFO queue holds futures, so later frames cannot overtake its head.
 * Each executor thread owns a worker with separate processor instances.
 * Synchronized methods protect queue access and coordinate shutdown.
 * Outstanding work is not yet bounded.
 */
public class FrameProcessingPool implements AutoCloseable {

    private final ExecutorService executor;
    private final ThreadLocal<FrameProcessingWorker> workers;

    // Queue positions represent submission order, regardless of when
    // the corresponding processing tasks finish.
    private final Queue<Future<PreparedFrame>> results = new ArrayDeque<>();

    /**
     * Creates a fixed-size pool with worker-private processing state.
     *
     * @param workerCount number of processing threads
     * @param workerFactory supplies fresh workers with initialized processors
     * @throws IllegalArgumentException if workerCount is not positive
     * @throws NullPointerException if workerFactory is null
     */
    public FrameProcessingPool(int workerCount, Supplier<FrameProcessingWorker> workerFactory) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }

        this.workers = ThreadLocal.withInitial(workerFactory);
        this.executor = Executors.newFixedThreadPool(workerCount);
    }

    /**
     * Submits a copied frame and queues its future in submission order.
     * The task owns and closes the frame after successful submission.
     * Rejected submission leaves ownership with the caller.
     *
     * @param frame copied input frame to process
     * @param info frame number and media timestamp
     * @param generation playback generation associated with the frame
     * @throws java.util.concurrent.RejectedExecutionException if the pool no longer accepts work
     */
    public synchronized void submit(Frame frame, InfoFrame info, long generation) {
        Future<PreparedFrame> result = executor.submit(() -> {
            try {
                return workers.get().process(frame, info, generation);
            } finally {
                frame.close();
            }
        });

        // Record the future now, rather than when processing finishes.
        // Synchronization prevents another submission or poll from racing with this submit and queue operation.
        results.add(result);
    }

    /**
     * Retrieves the queue head only when its task has finished.
     * An unfinished head remains queued, preventing later frames overtaking it.
     * Does not wait for processing, but may wait to acquire the pool monitor.
     * Failed tasks are removed and their failures propagated.
     *
     * @return oldest submitted result, or null if the queue is empty or its head is unfinished
     * @throws ExecutionException if worker creation or processing failed
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
        return next.get();
    }

    /**
     * Rejects new submissions and allows accepted tasks to finish.
     * Does not cancel tasks or wait for termination.
     */
    @Override
    public synchronized void close() {
        executor.shutdown();
    }
}