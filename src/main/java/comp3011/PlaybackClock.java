/*
 * Playback timing developed by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 * Codex helped plan the required methods and their functionality, Bailey implemented them.
 */
package comp3011;

/**
 * Maps media timestamps to monotonic playback deadlines.
 * Pausing freezes media time and resuming shifts subsequent deadlines.
 * Synchronised methods protect timing shared by presentation and audio.
 */
public class PlaybackClock {
    private final long firstTimestampUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private boolean paused;

    /**
     * Starts the clock from the first decoded video timestamp.
     *
     * @param firstTimestampUs first decoded video timestamp in microseconds
     * @param playbackStartNs  corresponding monotonic time in nanoseconds
     */
    public PlaybackClock(long firstTimestampUs, long playbackStartNs) {
        this.firstTimestampUs = firstTimestampUs;
        this.playbackStartNs = playbackStartNs;
    }

    /**
     * Pauses or resumes without advancing media time during the pause.
     * Repeating the current state leaves timing unchanged.
     *
     * @param paused whether playback should be paused
     * @param nowNs  current monotonic time in nanoseconds
     */
    public synchronized void setPaused(boolean paused, long nowNs) {
        if (this.paused == paused) {
            return;
        }

        if (paused) {
            pauseStartedNs = nowNs;
        } else {
            // Move the origin forward by the time spent paused.
            playbackStartNs += nowNs - pauseStartedNs;
        }

        this.paused = paused;
    }

    /**
     * Calculates a frame deadline using the current playback origin.
     * Recalculate after resume rather than retaining a pre-pause deadline.
     *
     * @param timestampUs media timestamp in microseconds
     * @return monotonic display deadline in nanoseconds
     */
    public synchronized long targetTimeNs(long timestampUs) {
        return playbackStartNs + relativeTimestampUs(timestampUs) * 1_000L;
    }

    /**
     * Checks whether a frame is due while playback is running.
     * Paused preview presentation is controlled separately by the model.
     *
     * @param timestampUs frame media timestamp in microseconds
     * @param nowNs       current monotonic time in nanoseconds
     * @return true if running and the deadline has been reached
     */
    public synchronized boolean isDue(long timestampUs, long nowNs) {
        return !paused && nowNs >= targetTimeNs(timestampUs);
    }

    /**
     * Calculates media time, freezing it while paused.
     *
     * @param nowNs current monotonic time in nanoseconds
     * @return media timestamp in microseconds
     */
    public synchronized long mediaTimestampUs(long nowNs) {
        long effectiveNowNs = paused ? pauseStartedNs : nowNs;
        return firstTimestampUs + (effectiveNowNs - playbackStartNs) / 1_000L;
    }

    /**
     * Calculates a nonnegative offset from the first video timestamp.
     *
     * @param timestampUs media timestamp in microseconds
     * @return elapsed media time in microseconds, clamped to zero
     */
    public long relativeTimestampUs(long timestampUs) {
        return Math.max(0, timestampUs - firstTimestampUs);
    }
}