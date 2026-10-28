/* Audio output tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AudioOutputTest {

    /**
     * Checks that zero and partial writes retain the correct byte offset.
     *
     * @throws Exception if coordination fails
     */
    @Test
    @Timeout(10)
    void shouldRetryPartialWrites() throws Exception {
        // Assemble
        AudioPlayer player = mock(AudioPlayer.class);
        AudioOutput output = new AudioOutput(new PlaybackClock(0, System.nanoTime()), 1);
        byte[] samples = {1, 2, 3, 4};
        CountDownLatch written = new CountDownLatch(1);
        when(player.write(samples, 0, 4)).thenReturn(0, 2);
        when(player.write(samples, 2, 2)).thenAnswer(call -> {
            written.countDown();
            return 2;
        });
        try {
            // Act
            output.start(player, 48_000, 1);
            output.enqueue(0, samples);
            boolean completed = written.await(3, TimeUnit.SECONDS);
            output.requestStop();
            boolean terminated = output.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            assertThat(completed).isTrue();
            assertThat(terminated).isTrue();
            assertThat(output.getFailure()).isNull();
            InOrder calls = inOrder(player);
            calls.verify(player).open(48_000, 1);
            calls.verify(player, times(2)).write(samples, 0, 4);
            calls.verify(player).write(samples, 2, 2);
            calls.verify(player).close();
        } finally {
            output.requestStop();
            output.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks that stopping wakes a producer waiting for a full queue.
     *
     * @throws Exception if coordination fails
     */
    @Test
    @Timeout(10)
    void shouldWakeProducerWhenStopped() throws Exception {
        // Assemble
        AudioPlayer player = mock(AudioPlayer.class);
        PlaybackClock clock = new PlaybackClock(0, System.nanoTime());
        clock.setPaused(true, System.nanoTime());
        AudioOutput output = new AudioOutput(clock, 1);
        FutureTask<Boolean> submission = new FutureTask<>(() -> {
            try {
                output.enqueue(0, new byte[]{1, 2});
                return false;
            } catch (RejectedExecutionException expected) {
                return true;
            }
        });
        Thread producer = new Thread(submission);
        try {
            // Act
            output.start(player, 48_000, 1);
            output.enqueue(0, new byte[]{0, 0});
            producer.start();
            Thread.State state;
            do {
                Thread.sleep(1);
                state = producer.getState();
            } while (state != Thread.State.WAITING && producer.isAlive());
            output.requestStop();
            boolean rejected = submission.get(3, TimeUnit.SECONDS);
            boolean terminated = output.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            assertThat(state).isEqualTo(Thread.State.WAITING);
            assertThat(rejected).isTrue();
            assertThat(terminated).isTrue();
            assertThat(output.getFailure()).isNull();
            verify(player, never()).write(any(byte[].class), anyInt(), anyInt());
            verify(player).close();
        } finally {
            output.requestStop();
            producer.interrupt();
            producer.join(1_000);
            output.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks that finished input writes accepted audio and drains the device before closing.
     *
     * @throws Exception if output startup or coordination fails
     */
    @Test
    @Timeout(10)
    void shouldDrainAcceptedAudioWhenInputFinishes() throws Exception {
        // Assemble
        AudioPlayer player = mock(AudioPlayer.class);
        AudioOutput output = new AudioOutput(new PlaybackClock(0, System.nanoTime()), 1);
        byte[] samples = {1, 2};
        when(player.write(samples, 0, 2)).thenReturn(2);

        try {
            // Act
            output.start(player, 48_000, 1);
            output.enqueue(0, samples);
            output.finishInput();
            boolean terminated = output.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            // Ensure accepted audio reaches the device before it is drained and closed.
            assertThat(terminated).isTrue();
            assertThat(output.getFailure()).isNull();
            InOrder calls = inOrder(player);
            calls.verify(player).open(48_000, 1);
            calls.verify(player).write(samples, 0, 2);
            calls.verify(player).drain();
            calls.verify(player).close();
            assertThatThrownBy(() -> output.enqueue(0, samples))
                .isInstanceOf(RejectedExecutionException.class);
        } finally {
            output.requestStop();
            output.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

}
