/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Owns the Java Sound device and accepts already copied PCM bytes.
 * All device operations must run on the owning playback thread.
 * The current single-threaded model owns this instance on the JavaFX thread.
 * Opening and warm-up may block; scheduling remains the caller's responsibility.
 */
public class AudioPlayer implements AutoCloseable {
    private static final long WRITE_TRACE_THRESHOLD_NS = 2_000_000L;
    private static final int AUDIO_WARMUP_BYTES = 4096;

    private SourceDataLine line; // Java Sound's audio-output interface - for us a destination, not a source.

    /**
     * Opens and starts signed 16-bit little-endian PCM output, then warms the device.
     * Nonpositive sample rates or channel counts perform no work.
     *
     * @param sampleRate samples per second for each channel
     * @param channels number of interleaved audio channels
     * @throws LineUnavailableException if the requested audio output cannot be opened
     * @throws IllegalArgumentException if no compatible output line supports the format
     */
    public void open(int sampleRate, int channels) throws LineUnavailableException {
        if (sampleRate <= 0 || channels <= 0) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        warmUpLine();
    }

    /**
     * Writes at most the device's currently available byte capacity.
     * The caller retains ownership and tracks any unwritten remainder.
     *
     * @param samples interleaved little-endian PCM bytes
     * @param offset byte offset of the next unwritten data
     * @param length maximum number of bytes to write
     * @return bytes accepted by the device, or zero if no write can be attempted
     * @throws NullPointerException if samples is null while output is open
     * @throws IndexOutOfBoundsException if the attempted byte range is invalid
     * @throws IllegalArgumentException if the attempted write is not a whole number of audio frames
     */
    public int write(byte[] samples, int offset, int length) {
        if (line == null || samples.length == 0 || length <= 0) {
            return 0;
        }

        // Try and avoid blocking writes to the audio stream by only adding equal or less than the number of bytes
        // available. We return only the bytes we were able to write without overflowing the buffer, so the remaining
        // bytes can be tracked and written later. This avoids audio buffer blocking in a cooperative multitasking
        // architecture - i.e. we are being cooperative and not stalling the thread on line.write below!
        int frameSize = line.getFormat().getFrameSize();
        if (offset % frameSize != 0 || length % frameSize != 0) {
            throw new IllegalArgumentException("Audio data must contain whole frames");
        }
        int availableBeforeWrite = line.available();
        int byteCount = Math.min(length, availableBeforeWrite);
        byteCount -= byteCount % frameSize;
        if (byteCount <= 0) {
            return 0;
        }

        long startNs = System.nanoTime();
        int written = line.write(samples, offset, byteCount); // Will block if we get it wrong.
        long elapsedNs = System.nanoTime() - startNs;
        if (elapsedNs >= WRITE_TRACE_THRESHOLD_NS) {
            // Hopefully you'll never see this.
            System.out.printf(
                "WARNING: Audio write blocked for %dus writing %d bytes, line available before write: %d bytes%n",
                elapsedNs / 1000,
                written,
                availableBeforeWrite);
        }
        return written;
    }

    /**
     * Pauses the device while retaining buffered audio.
     */
    public void pause() {
        if (line != null) {
            line.stop();
        }
    }

    /**
     * Resumes the device after a pause.
     */
    public void resume() {
        if (line != null) {
            line.start();
        }
    }

    /**
     * Checks whether the Java Sound line buffer is empty.
     *
     * @return true if the line is closed or can accept its full buffer capacity
     */
    public boolean isBufferEmpty() {
        return line == null || line.available() >= line.getBufferSize();
    }

    /**
     * Discards audio queued in the device, if output is open.
     */
    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    /**
     * Stops output, discards queued device audio and closes the owned line.
     * Repeated calls perform no work after successful closure.
     */
    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }

    /**
     * Writes silence to reduce device wake-up delay before real audio is scheduled.
     * Flushes remaining silence so it does not delay playback.
     */
    private void warmUpLine() {
        int frameSize = line.getFormat().getFrameSize();
        int byteCount = Math.min(AUDIO_WARMUP_BYTES, line.getBufferSize());
        byteCount -= byteCount % frameSize;
        byte[] silence = new byte[byteCount]; // All zeros.
        line.write(silence, 0, silence.length); // May play, but harmless.
        line.flush(); // Get rid of the rest straight away, hopefully warm up is done.
    }

}
