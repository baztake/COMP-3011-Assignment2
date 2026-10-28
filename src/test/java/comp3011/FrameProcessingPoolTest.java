/*
 * Pool regression test, written with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FrameProcessingPoolTest {

    /**
     * Checks that the pool returns results in submission order, even when a later task progresses while the first task is blocked.
     * <p>
     * A latch holds the first task while a second latch signals progress from the second task. Polling before releasing the first task must
     * return null, preventing later frames from overtaking it. After release, results must follow submission order.
     */
    @Test
    @Timeout(10)
    void shouldReturnResultsInSubmissionOrder() throws Exception {
        // Assemble
        FrameProcessingWorker worker = mock(FrameProcessingWorker.class);

        Frame firstInput = mock(Frame.class);
        Frame secondInput = mock(Frame.class);

        InfoFrame firstInfo = new InfoFrame(1, 0);
        InfoFrame secondInfo = new InfoFrame(2, 0);

        PreparedFrame firstResult = new PreparedFrame(null, firstInfo, 0, 0L);
        PreparedFrame secondResult = new PreparedFrame(null, secondInfo, 0, 0L);

        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);

        // Mocking the workers
        // For the first frame, we wait for the latch to be released, then return the result
        when(worker.process(firstInput, firstInfo, 0)).thenAnswer(_ -> {
            releaseFirst.await();
            return firstResult;
        });
        // For the second frame, we immediately begin counting down the latch then return
        when(worker.process(secondInput, secondInfo, 0)).thenAnswer(_ -> {
            secondProcessed.countDown();
            return secondResult;
        });

        // We define our pool using the mocked worker
        try (FrameProcessingPool pool = new FrameProcessingPool(2, 2, () -> worker)) {
            // Act
            // We submit our two frames to the pool, the second frame is already processing
            pool.submit(firstInput, firstInfo, 0);
            pool.submit(secondInput, secondInfo, 0);

            // We wait up to 3 seconds for the second frame to be (mock) processed
            boolean secondProgressed = secondProcessed.await(3, TimeUnit.SECONDS);

            // At this point, frame 2 is complete, still waiting on frame 1, expect this to be null
            PreparedFrame resultWhileFirstBlocked = pool.pollReady();

            // Now we open frame 1 latch, allowing it to start processing
            releaseFirst.countDown();

            // We wait until we have a result from pool, we expect this to be frame 1 followed by frame 2
            PreparedFrame actualFirst = awaitResult(pool);
            PreparedFrame actualSecond = awaitResult(pool);
            PreparedFrame remainingResult = pool.pollReady();

            // Assert

            // Ensure the second frame was processed
            assertThat(secondProgressed).isTrue();

            // While we were still waiting on Frame 1, the result is null
            assertThat(resultWhileFirstBlocked).isNull();

            // Ensure the results are in the correct order
            assertThat(actualFirst).isSameAs(firstResult);
            assertThat(actualSecond).isSameAs(secondResult);
            // Ensure we don't get any other frames
            assertThat(remainingResult).isNull();
        } finally {
            // Release the worker even when the test fails or times out.
            releaseFirst.countDown();
        }
    }

    /**
     * Checks that submission waits while the result queue is full.
     */
    @Test
    @Timeout(5)
    void shouldWaitWhenQueueIsFull() throws Exception {
        // Assemble
        FrameProcessingWorker worker = mock(FrameProcessingWorker.class);
        Frame firstInput = mock(Frame.class);
        Frame secondInput = mock(Frame.class);
        InfoFrame info = new InfoFrame(1, 0);

        // Mocking the frame processing
        when(worker.process(firstInput, info, 0)).thenReturn(new PreparedFrame(null, info, 0, 0L));

        // Create new pool with max queue size of 1
        try (FrameProcessingPool pool = new FrameProcessingPool(1, 1, () -> worker)) {
            // Submit first frame
            pool.submit(firstInput, info, 0);

            // Thread with task to submit second frame
            FutureTask<Void> submission = new FutureTask<>(() -> {
                pool.submit(secondInput, info, 0);
                return null;
            });
            Thread producer = new Thread(submission);

            try {
                // Act
                producer.start();
                Thread.State state;

                // Wait for producer thread to enter waiting state
                // Test has a timeout in case it doesn't
                do {
                    Thread.sleep(1);
                    state = producer.getState();
                } while (state != Thread.State.WAITING && producer.isAlive());

                // Assert
                assertThat(state).isEqualTo(Thread.State.WAITING);
            } finally {
                pool.close();
                producer.interrupt();
                producer.join(1_000);
            }
        }
    }

    /**
     * Checks that polling frees queue space and wakes a waiting submitter.
     */
    @Test
    @Timeout(5)
    void shouldWakeWaitingSubmitterWhenPolled() throws Exception {
        // Assemble
        FrameProcessingWorker worker = mock(FrameProcessingWorker.class);
        Frame firstInput = mock(Frame.class);
        Frame secondInput = mock(Frame.class);
        InfoFrame info = new InfoFrame(1, 0);
        PreparedFrame firstResult = new PreparedFrame(null, info, 0, 0L);

        when(worker.process(firstInput, info, 0)).thenReturn(firstResult);

        // Create new pool with max queue size of 1
        try (FrameProcessingPool pool = new FrameProcessingPool(1, 1, () -> worker)) {
            // Submit first frame, queue is now full
            pool.submit(firstInput, info, 0);

            // Create new submission task
            FutureTask<Void> submission = new FutureTask<>(() -> {
                pool.submit(secondInput, info, 0);
                return null;
            });
            Thread producer = new Thread(submission);

            try {
                // Act
                producer.start();

                // Wait for producer to enter waiting state
                Thread.State state;
                do {
                    Thread.sleep(1);
                    state = producer.getState();
                } while (state != Thread.State.WAITING && producer.isAlive());

                // Get result from pool
                PreparedFrame result = awaitResult(pool);

                // Submit frame 2, will fail on timeout if thread is still waiting
                submission.get(3, TimeUnit.SECONDS);

                // Assert
                // Assert the thread was waiting, first result was returned
                assertThat(state).isEqualTo(Thread.State.WAITING);
                assertThat(result).isSameAs(firstResult);
            } finally {
                pool.close();
                producer.interrupt();
                producer.join(1_000);
            }
        }
    }

    /**
     * Checks that stopping cancels work, closes accepted inputs and terminates workers.
     *
     * @throws Exception if processing or test coordination fails
     */
    @Test
    @Timeout(10)
    void shouldCancelWorkAndReleaseInputs() throws Exception {
        // Assemble
        FrameProcessingWorker worker = mock(FrameProcessingWorker.class);
        Frame runningInput = mock(Frame.class);
        Frame queuedInput = mock(Frame.class);
        InfoFrame info = new InfoFrame(1, 0);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Mocking the frame processing
        // Hold the running task until it is interrupted or released
        when(worker.process(runningInput, info, 0)).thenAnswer(_ -> {
            started.countDown();
            release.await();
            return new PreparedFrame(null, info, 0, 0L);
        });

        // Create a pool with one worker so the second frame stays queued
        try (FrameProcessingPool pool = new FrameProcessingPool(1, 2, () -> worker)) {
            try {
                // Act
                pool.submit(runningInput, info, 0);

                // Wait up to 3 seconds for the first frame to start processing
                boolean processingStarted = started.await(3, TimeUnit.SECONDS);
                pool.submit(queuedInput, info, 0);

                // Stop the pool and wait for workers to terminate
                pool.requestStop();
                boolean terminated = pool.awaitTermination(3, TimeUnit.SECONDS);

                // Assert
                // Ensure processing started and the worker stopped
                assertThat(processingStarted).isTrue();
                assertThat(terminated).isTrue();

                // Ensure both inputs were closed exactly once
                verify(runningInput).close();
                verify(queuedInput).close();

                // Ensure the queued frame was never processed
                verify(worker, never()).process(queuedInput, info, 0);
            } finally {
                // Release blocked processing if the test fails or times out
                release.countDown();
                pool.requestStop();
                pool.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    private PreparedFrame awaitResult(FrameProcessingPool pool) throws Exception {
        PreparedFrame result;
        while ((result = pool.pollReady()) == null) {
            Thread.sleep(1);
        }
        return result;
    }
}
