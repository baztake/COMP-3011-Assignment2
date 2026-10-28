/*
 * Video decoding extracted from original VideoPlayerModel.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.util.Objects;

/**
 * Owns video decoding and captures metadata from decoded frames.
 * All methods must be called by the owning decoder thread.
 * Returned frames are borrowed and must be copied before another read or close.
 * Use try-with-resources so failed opening or reading still attempts cleanup.
 */
public class VideoDecoder implements AutoCloseable {
    private final FFmpegFrameGrabber grabber;
    private final String mediaName;
    private InfoVideo videoInfo;

    /**
     * Takes ownership of an unstarted grabber for one playback session.
     * The caller must not use the grabber independently after construction.
     *
     * @param grabber unstarted grabber to configure, read and close
     * @param mediaName media name without its file extension
     * @throws NullPointerException if grabber or mediaName is null
     */
    public VideoDecoder(FFmpegFrameGrabber grabber, String mediaName) {
        this.grabber = Objects.requireNonNull(grabber);
        this.mediaName = Objects.requireNonNull(mediaName);
    }

    /**
     * Opens decoding and optionally seeks to the requested starting position.
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

        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    /**
     * Reads the next video frame and snapshots its frame information.
     * The first frame supplies video metadata and is also returned to the caller.
     * The returned frame remains owned by the grabber; do not close it separately.
     *
     * @return borrowed frame and its information, or null at the end of video
     * @throws FrameGrabber.Exception if decoding fails
     */
    public DecodedFrame read() throws FrameGrabber.Exception {
        Frame frame = grabber.grabImage();
        if (frame == null) {
            return null;
        }

        if (videoInfo == null) {
            // Actual decoded layout is needed, particularly depth and stride.
            double frameRate = grabber.getFrameRate();
            int frameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;

            videoInfo = new InfoVideo(
                mediaName, grabber.getLengthInVideoFrames(),
                frame.imageWidth, frame.imageHeight,
                frame.imageDepth, frame.imageChannels, frame.imageStride,
                frameRate, (int) Math.round(frameRate), frameDurationUs,
                grabber.getPixelFormat()
            );
        }

        // Snapshot before another grab can change the grabber's current position.
        InfoFrame info = new InfoFrame(grabber.getFrameNumber(), grabber.getTimestamp());
        return new DecodedFrame(frame, info);
    }

    /**
     * Returns metadata captured from the first decoded video frame.
     *
     * @return video metadata, or null until a video frame has been decoded
     */
    public InfoVideo videoInfo() {
        return videoInfo;
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
     * Pairs a decoded frame with an immutable information snapshot.
     * Copy the frame before handing it to a processing task.
     *
     * @param frame frame valid only until the next read or decoder close
     * @param info frame number and media timestamp captured during this read
     */
    public record DecodedFrame(Frame frame, InfoFrame info) {
    }
}
