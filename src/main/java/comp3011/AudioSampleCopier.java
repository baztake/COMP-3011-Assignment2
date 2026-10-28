/*
 * Audio sample conversion extracted from the original AudioPlayer.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import org.bytedeco.javacv.Frame;

import java.nio.Buffer;
import java.nio.ShortBuffer;

/**
 * Copies supported 16-bit audio samples into interleaved little-endian PCM bytes.
 * Each owning decoder thread must use its own instance because the scratch buffer is mutable.
 * Input frames and sample buffers remain borrowed; returned arrays are independent copies.
 * The current single-threaded model owns its copier on the JavaFX thread.
 */
public class AudioSampleCopier {
    private byte[] outputBuffer = new byte[0];

    /**
     * Copies remaining packed or planar short samples without advancing source positions.
     * Each returned array can outlive decoder buffer reuse and subsequent copying.
     *
     * @param frame borrowed frame containing decoded audio samples
     * @return independent PCM bytes, or an empty array for absent or unsupported samples
     * @throws NullPointerException if frame is null
     */
    public byte[] copySamples(Frame frame) {
        if (frame.samples == null || frame.samples.length == 0) {
            return new byte[0];
        }

        int byteCount = copySamplesToOutputBuffer(frame);
        if (byteCount <= 0) {
            return new byte[0];
        }

        // The scratch buffer is reused; each output packet needs independent storage.
        byte[] samples = new byte[byteCount];
        System.arraycopy(outputBuffer, 0, samples, 0, byteCount);
        return samples;
    }

    /**
     * Converts supported samples into the reusable scratch buffer.
     * Planar channels use the shortest remaining channel length.
     *
     * @param frame borrowed frame containing decoded audio samples
     * @return converted byte count, or zero for unsupported sample buffers
     */
    private int copySamplesToOutputBuffer(Frame frame) {
        Buffer[] samples = frame.samples;
        int channels = frame.audioChannels;

        if (channels > 1 && samples.length == channels && samples[0] instanceof ShortBuffer) {
            ShortBuffer[] planarSamples = new ShortBuffer[channels];
            int sampleCount = Integer.MAX_VALUE;

            for (int channel = 0; channel < channels; channel++) {
                if (!(samples[channel] instanceof ShortBuffer channelSamples)) {
                    return 0;
                }
                // Duplicate positions so conversion does not consume borrowed decoder buffers.
                planarSamples[channel] = channelSamples.duplicate();
                sampleCount = Math.min(sampleCount, planarSamples[channel].remaining());
            }

            ensureOutputBufferCapacity(sampleCount * channels * Short.BYTES);
            return copyPlanarSamples(planarSamples, sampleCount);
        }

        if (samples[0] instanceof ShortBuffer packedSamples) {
            ShortBuffer source = packedSamples.duplicate();
            ensureOutputBufferCapacity(source.remaining() * Short.BYTES);
            return copyPackedSamples(source);
        }

        return 0;
    }

    /**
     * Copies already interleaved samples into the scratch buffer.
     *
     * @param source duplicate buffer whose position may advance
     * @return number of PCM bytes written
     */
    private int copyPackedSamples(ShortBuffer source) {
        int outputIndex = 0;
        while (source.hasRemaining()) {
            short sample = source.get();
            outputBuffer[outputIndex++] = (byte) (sample & 0xff);
            outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
        }
        return outputIndex;
    }

    /**
     * Interleaves separate channel samples into the scratch buffer.
     *
     * @param planarSamples duplicate channel buffers whose positions may advance
     * @param sampleCount number of samples to copy from each channel
     * @return number of PCM bytes written
     */
    private int copyPlanarSamples(ShortBuffer[] planarSamples, int sampleCount) {
        int outputIndex = 0;
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            for (ShortBuffer channelSamples : planarSamples) {
                short sample = channelSamples.get();
                outputBuffer[outputIndex++] = (byte) (sample & 0xff);
                outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
            }
        }
        return outputIndex;
    }

    /**
     * Grows scratch storage only when the current packet needs more space.
     *
     * @param byteCount required capacity in bytes
     */
    private void ensureOutputBufferCapacity(int byteCount) {
        if (outputBuffer.length < byteCount) {
            outputBuffer = new byte[byteCount];
        }
    }

}
