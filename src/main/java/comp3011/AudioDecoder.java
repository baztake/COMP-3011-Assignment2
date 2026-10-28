/*
 * Audio decoding prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.util.Objects;

/**
 * Owns an independent grabber used for audio decoding and copies samples before the next read.
 * All methods must be called by the owning decoder thread.
 * Use try-with-resources so failed opening or reading still attempts cleanup.
 */
public class AudioDecoder implements AutoCloseable {
    private final FFmpegFrameGrabber grabber;
    private final AudioSampleCopier copier = new AudioSampleCopier();

    /**
     * Takes ownership of an unstarted grabber for one audio stream.
     *
     * @param grabber unstarted grabber to configure, read and close
     * @throws NullPointerException if grabber is null
     */
    public AudioDecoder(FFmpegFrameGrabber grabber) {
        this.grabber = Objects.requireNonNull(grabber);
    }

    /**
     * Opens audio decoding and optionally seeks to the requested position.
     * Call once, inside the decoder's try-with-resources block.
     *
     * @param startTimestampUs starting media position in microseconds
     * @throws IllegalArgumentException if startTimestampUs is negative
     * @throws FrameGrabber.Exception if opening or seeking fails
     */
    public void open(long startTimestampUs) throws FrameGrabber.Exception {
        if (startTimestampUs < 0) {
            throw new IllegalArgumentException("Starting timestamp must not be negative");
        }

        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    /**
     * Reports whether the opened media contains audio.
     * Call after opening the decoder.
     *
     * @return true if an audio stream is available
     */
    public boolean hasAudio() {
        return grabber.hasAudio();
    }

    /**
     * Returns the opened audio stream's sample rate.
     * Call after opening the decoder.
     *
     * @return samples per second for each channel
     */
    public int sampleRate() {
        return grabber.getSampleRate();
    }

    /**
     * Returns the opened audio stream's channel count.
     * Call after opening the decoder.
     *
     * @return number of interleaved audio channels
     */
    public int channels() {
        return grabber.getAudioChannels();
    }

    /**
     * Reads an audio frame and copies its samples into independent storage.
     *
     * @return copied audio and its timestamp, or null at end of audio
     * @throws FrameGrabber.Exception if decoding fails
     */
    public DecodedAudio read() throws FrameGrabber.Exception {
        Frame frame = grabber.grabSamples();
        if (frame == null) {
            return null;
        }

        long timestampUs = grabber.getTimestamp();
        return new DecodedAudio(timestampUs, copier.copySamples(frame));
    }

    /**
     * Closes the grabber on its owning decoder thread.
     *
     * @throws FrameGrabber.Exception if closing fails
     */
    @Override
    public void close() throws FrameGrabber.Exception {
        grabber.close();
    }

    /**
     * Holds copied PCM bytes and the timestamp captured during decoding.
     * AudioOutput owns the samples after successful submission.
     *
     * @param timestampUs media timestamp in microseconds
     * @param samples copied interleaved PCM bytes
     */
    public record DecodedAudio(long timestampUs, byte[] samples) {
    }
}
