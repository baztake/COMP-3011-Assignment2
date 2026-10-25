/*
 * Pool regression test, written with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        PreparedFrame firstResult = new PreparedFrame(null, firstInfo, 0);
        PreparedFrame secondResult = new PreparedFrame(null, secondInfo, 0);

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
        try (var pool = new FrameProcessingPool(2, () -> worker)) {
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

    private PreparedFrame awaitResult(FrameProcessingPool pool) throws Exception {
        PreparedFrame result;
        while ((result = pool.pollReady()) == null) {
            Thread.sleep(1);
        }
        return result;
    }
}