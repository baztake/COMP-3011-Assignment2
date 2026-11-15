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

    public static final long CHUNK_DURATION_US = 30_000_000;
    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_SAMPLES = SAMPLE_RATE * 30;

    /**
     * Produces at most 30 seconds of audio from the requested video position.
     *
     * @param file original video file
     * @param startUs chunk start on the video's logical timeline in microseconds
     * @return bounded audio, an empty WAV array means there is no audio in this interval
     * @throws IllegalArgumentException if the starting position is negative
     * @throws Exception if decoding, WAV writing or cancellation fails
     */
    public AudioChunk extract(File file, long startUs) throws Exception {
        if (startUs < 0) {
            throw new IllegalArgumentException("Negative audio chunk position");
        }

        checkInterrupted();

        VideoTiming timing = videoTiming(file);
        long chunkOrigin = Math.addExact(timing.originUs(), startUs);
        byte[] pcm = new byte[MAX_SAMPLES * Short.BYTES];
        int usedSamples = 0;
        boolean last = timing.durationUs() > 0 && startUs >= timing.durationUs() - CHUNK_DURATION_US;

        checkInterrupted();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file);
        // Configure our own reader for uploads, playback keeps its original audio format and reader.
        grabber.setSampleRate(SAMPLE_RATE);
        grabber.setAudioChannels(1);
        try (AudioDecoder audio = new AudioDecoder(grabber)) {
            // Seek slightly early so a packet crossing the chunk boundary can still be trimmed accurately.
            audio.open(startUs == 0 ? 0 : Math.max(0, chunkOrigin - 1_000_000));

            if (!audio.hasAudio()) {
                return new AudioChunk(new byte[0], startUs, 0, true);
            }

            while (true) {
                checkInterrupted();
                AudioDecoder.DecodedAudio packet = audio.read();
                if (packet == null) {
                    last = true;
                    break;
                }

                long firstSample = Math.round((packet.timestampUs() - chunkOrigin) * (SAMPLE_RATE / 1_000_000.0));
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
            return new AudioChunk(new byte[0], startUs, 0, last);
        }

        return new AudioChunk(writeWav(pcm, usedSamples), startUs, usedSamples * 1_000_000L / SAMPLE_RATE, last);
    }

    /**
     * Finds playback's position zero and duration using an independent reader.
     *
     * @param file original video
     * @return first video timestamp and media duration in microseconds
     * @throws Exception if the video cannot be opened or has no frames
     */
    private VideoTiming videoTiming(File file) throws Exception {
        try (VideoDecoder video = new VideoDecoder(new FFmpegFrameGrabber(file), VideoDecoder.mediaName(file))) {
            video.open(0);
            VideoDecoder.DecodedFrame first = video.read();
            if (first == null) {
                throw new IOException("No video frames found");
            }

            return new VideoTiming(first.info().timestampUs(), video.durationUs());
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
     * @param last whether this is the final chunk, or the file has no audio stream
     */
    public record AudioChunk(byte[] wav, long offsetUs, long durationUs, boolean last) {
    }

    private record VideoTiming(long originUs, long durationUs) {
    }
}
