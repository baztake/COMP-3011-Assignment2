/* Subtitle audio extraction prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Owns independent decoders on the subtitle worker, doesn't touch playback's grabbers or audio device.
 */
public final class SubtitleAudioExtractor {

    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_SAMPLES = SAMPLE_RATE * 30;

    /**
     * Produces at most 30 seconds of audio.
     *
     * @param file original video file
     * @return opening audio on the video's logical timeline, or null if it has no audio in this interval
     * @throws Exception if decoding, WAV writing or cancellation fails
     */
    public AudioChunk extract(File file) throws Exception {
        checkInterrupted();

        long videoOrigin = firstVideoTimestamp(file);
        byte[] pcm = new byte[MAX_SAMPLES * Short.BYTES];
        int usedSamples = 0;

        checkInterrupted();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file);
        // Configure our own reader for uploads, playback keeps its original audio format and reader.
        grabber.setSampleRate(SAMPLE_RATE);
        grabber.setAudioChannels(1);
        try (AudioDecoder audio = new AudioDecoder(grabber)) {
            audio.open(0);

            if (!audio.hasAudio()) {
                return null;
            }

            while (true) {
                checkInterrupted();
                AudioDecoder.DecodedAudio packet = audio.read();
                if (packet == null) {
                    break;
                }

                long firstSample = Math.round((packet.timestampUs() - videoOrigin) * (SAMPLE_RATE / 1_000_000.0));
                if (firstSample >= MAX_SAMPLES) {
                    break;
                }

                // AudioDecoder returns independent little-endian PCM bytes, ready to copy into the chunk.
                byte[] samples = packet.samples();
                int count = samples.length / Short.BYTES;
                long endSample = Math.min(MAX_SAMPLES, firstSample + count);
                if (endSample <= 0 || count == 0) {
                    continue;
                }

                int destination = (int) Math.max(0, firstSample);
                int source = (int) (destination - firstSample);
                int length = (int) endSample - destination;
                // Preserve gaps as silence and trim audio preceding video, rather than shifting the dialogue.
                System.arraycopy(samples, source * Short.BYTES, pcm, destination * Short.BYTES, length * Short.BYTES);
                usedSamples = Math.max(usedSamples, (int) endSample);
            }
        }

        checkInterrupted();
        if (usedSamples == 0) {
            return null;
        }

        return new AudioChunk(writeWav(pcm, usedSamples), 0, usedSamples * 1_000_000L / SAMPLE_RATE);
    }

    /**
     * Finds playback's position zero using an independent reader.
     *
     * @param file original video
     * @return first video timestamp in microseconds
     * @throws Exception if the video cannot be opened or has no frames
     */
    private long firstVideoTimestamp(File file) throws Exception {
        try (FFmpegFrameGrabber video = new FFmpegFrameGrabber(file)) {
            video.start();
            if (video.grabImage() == null) {
                throw new IOException("No video frames found");
            }

            return video.getTimestamp();
        }
    }

    /**
     * Lets Java Sound write the WAV header for only the populated audio interval.
     *
     * @param pcm mono 16-bit samples stored in little-endian byte order
     * @param sampleCount populated samples to encode
     * @return encoded WAV audio
     * @throws IOException if WAV encoding fails
     */
    private byte[] writeWav(byte[] pcm, int sampleCount) throws IOException {
        ByteArrayOutputStream wav = new ByteArrayOutputStream(sampleCount * Short.BYTES + 64);
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
            new ByteArrayInputStream(pcm, 0, sampleCount * Short.BYTES), format, sampleCount)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav);
        }

        return wav.toByteArray();
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Subtitle extraction cancelled");
        }
    }

    /**
     * @param wav        encoded audio
     * @param offsetUs   media offset of the chunk
     * @param durationUs encoded duration
     */
    public record AudioChunk(byte[] wav, long offsetUs, long durationUs) {
    }
}
