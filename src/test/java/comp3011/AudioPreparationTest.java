/*
 * Audio preparation regression tests written with assistance from OpenAI Codex.
 */
package comp3011;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AudioPreparationTest {

    /**
     * Checks that decoded packets reach audio output in order and EOF finishes input.
     *
     * @throws Exception if preparation or coordination fails
     */
    @Test
    @Timeout(10)
    void shouldSubmitAudioAndFinishInput() throws Exception {
        // Assemble
        AudioDecoder decoder = mock(AudioDecoder.class);
        AudioOutput output = mock(AudioOutput.class);
        AudioPreparation preparation = new AudioPreparation(output);
        byte[] first = {1, 2};
        byte[] second = {3, 4};
        when(decoder.hasAudio()).thenReturn(true);
        when(decoder.sampleRate()).thenReturn(48_000);
        when(decoder.channels()).thenReturn(1);
        when(output.isReady()).thenReturn(true);
        when(decoder.read()).thenReturn(
            new AudioDecoder.DecodedAudio(100, first),
            new AudioDecoder.DecodedAudio(200, second)
        ).thenReturn(null);
        when(output.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);

        try {
            // Act
            preparation.start(decoder, 0);
            boolean terminated = preparation.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            assertThat(terminated).isTrue();
            assertThat(preparation.isProbeComplete()).isTrue();
            assertThat(preparation.isAudioAvailable()).isTrue();
            assertThat(preparation.isFirstPacketQueued()).isTrue();
            assertThat(preparation.isOutputReady()).isTrue();
            assertThat(preparation.getFirstAudioTimestampUs()).isEqualTo(100);
            assertThat(preparation.getFailure()).isNull();
            verify(output).start(any(AudioPlayer.class), eq(48_000), eq(1));
            InOrder calls = inOrder(output);
            calls.verify(output).enqueue(100, first);
            calls.verify(output).enqueue(200, second);
            calls.verify(output).finishInput();
            verify(decoder).close();
        } finally {
            preparation.requestStop();
            preparation.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks that stopping interrupts a decoder blocked while submitting audio.
     *
     * @throws Exception if preparation or coordination fails
     */
    @Test
    @Timeout(10)
    void shouldStopWhenAudioSubmissionIsBlocked() throws Exception {
        // Assemble
        AudioDecoder decoder = mock(AudioDecoder.class);
        AudioOutput output = mock(AudioOutput.class);
        AudioPreparation preparation = new AudioPreparation(output);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(decoder.hasAudio()).thenReturn(true);
        when(decoder.sampleRate()).thenReturn(48_000);
        when(decoder.channels()).thenReturn(1);
        when(decoder.read()).thenReturn(new AudioDecoder.DecodedAudio(100, new byte[]{1, 2}));
        when(output.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);

        // Mocking a full audio queue that waits until interrupted or released.
        doAnswer(call -> {
            submissionStarted.countDown();
            release.await();
            return null;
        }).when(output).enqueue(eq(100L), any(byte[].class));

        try {
            // Act
            preparation.start(decoder, 0);
            boolean started = submissionStarted.await(3, TimeUnit.SECONDS);
            preparation.requestStop();
            boolean terminated = preparation.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            assertThat(started).isTrue();
            assertThat(terminated).isTrue();
            assertThat(preparation.getFailure()).isNull();
            verify(output, atLeastOnce()).requestStop();
            verify(output, never()).finishInput();
            verify(decoder).close();
        } finally {
            release.countDown();
            preparation.requestStop();
            preparation.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks that a file without audio completes without starting the device.
     *
     * @throws Exception if preparation or coordination fails
     */
    @Test
    @Timeout(10)
    void shouldCompleteWhenAudioIsAbsent() throws Exception {
        // Assemble
        AudioDecoder decoder = mock(AudioDecoder.class);
        AudioOutput output = mock(AudioOutput.class);
        AudioPreparation preparation = new AudioPreparation(output);
        when(decoder.hasAudio()).thenReturn(false);
        when(output.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);

        try {
            // Act
            preparation.start(decoder, 0);
            boolean terminated = preparation.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            assertThat(terminated).isTrue();
            assertThat(preparation.isProbeComplete()).isTrue();
            assertThat(preparation.isAudioAvailable()).isFalse();
            assertThat(preparation.isFirstPacketQueued()).isFalse();
            assertThat(preparation.getFailure()).isNull();
            verify(output, never()).start(any(AudioPlayer.class), anyInt(), anyInt());
            verify(decoder).close();
        } finally {
            preparation.requestStop();
            preparation.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

}
