/*
 * Concurrency documentation written with assistance from OpenAI Codex.
 */
package comp3011;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Processes frames concurrently and returns results in submission order.
 * A FIFO queue holds futures, so later frames cannot overtake its head.
 * Each executor thread owns a worker with separate processor instances.
 * Synchronised methods protect queue access and coordinate shutdown.
 * The factory must supply separate, initialised workers and processor instances.
 */
@Slf4j
public class FrameProcessingPool implements AutoCloseable {

    private final ExecutorService executor;
    private final int maxQueuedFrames;
    private final ThreadLocal<FrameProcessingWorker> workers;

    // Queue positions represent submission order, regardless of when the corresponding processing tasks finish.
    private final Queue<FrameTask> results = new ArrayDeque<>();

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
        this.executor = Executors.newFixedThreadPool(
            workerCount,
            Thread.ofPlatform().name("frame-worker-", 0).factory()
        );
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

        FrameTask result = new FrameTask(frame, info, generation);

        // Execute directly so the executor does not wrap our task in another future.
        // Rejection leaves the input owned by the caller.
        executor.execute(result);

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
        FrameTask next = results.peek();

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
     * Checks whether any submitted results remain in the queue.
     *
     * @return true if a queued result has not been polled
     */
    synchronized boolean hasPendingResults() {
        return !results.isEmpty();
    }

    /**
     * Waits for the oldest submitted task without removing its result.
     * Call only from the startup thread before publishing the pool.
     * The monitor is released while the task runs.
     *
     * @throws IllegalStateException if no task has been submitted
     * @throws ExecutionException    if worker creation or processing failed
     * @throws InterruptedException  if interrupted while waiting
     */
    void awaitFirstResult() throws ExecutionException, InterruptedException {
        FrameTask first;
        synchronized (this) {
            first = results.peek();
        }
        if (first == null) {
            throw new IllegalStateException("No frame has been submitted");
        }
        first.get();
    }

    /**
     * Rejects submissions, cancels accepted work and discards queued results.
     * Running tasks retain their inputs until execution exits.
     * Queued tasks release their inputs without running processors.
     * Does not wait for termination and can escalate a graceful close.
     * Input cleanup failures are logged without stopping remaining cancellation.
     */
    public synchronized void requestStop() {
        closed = true;

        try {
            // Interrupt workers and remove executor-queued work.
            // Our results queue still tracks those tasks for cancellation.
            executor.shutdownNow();

            for (FrameTask task : results) {
                task.cancel(true);
            }
        } finally {
            results.clear();
            notifyAll();
        }
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

    /**
     * Waits for executor tasks to exit after close or requestStop has returned.
     * Call from a lifecycle thread, never JavaFX or a processing worker.
     * Confirms worker termination, not successful input cleanup.
     *
     * @param timeout maximum time to wait
     * @param unit    unit of the timeout
     * @return true if executor tasks have exited, otherwise false on timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    /**
     * Owns one accepted input and arbitrates execution against queued cancellation.
     * The atomic claim selects the only path allowed to close the input.
     */
    private class FrameTask extends FutureTask<PreparedFrame> {
        private final Frame input;
        private final AtomicBoolean inputClaimed = new AtomicBoolean();

        /**
         * Creates an unsubmitted task without transferring input ownership.
         *
         * @param input      copied frame to process after acceptance
         * @param info       frame number and media timestamp
         * @param generation playback generation associated with the frame
         */
        private FrameTask(Frame input, InfoFrame info, long generation) {
            super(() -> workers.get().process(input, info, generation));
            this.input = input;
        }

        /**
         * Claims the input for execution and closes it when execution exits.
         * A cancellation that already claimed the input prevents execution.
         * The future can complete before the final input close.
         */
        @Override
        public void run() {
            if (inputClaimed.compareAndSet(false, true)) {
                try {
                    super.run();
                } finally {
                    releaseInput();
                }
            }
        }

        /**
         * Releases cancelled input only when execution has not claimed it.
         * Running input cleanup remains the responsibility of run.
         */
        @Override
        protected void done() {
            if (isCancelled() && inputClaimed.compareAndSet(false, true)) {
                releaseInput();
            }
        }

        /**
         * Attempts to close input and logs any cleanup failure.
         * A failed close must not prevent cancellation of other queued tasks.
         */
        private void releaseInput() {
            try {
                input.close();
            } catch (RuntimeException failure) {
                log.error("Failed to close frame input", failure);
            }
        }
    }
}