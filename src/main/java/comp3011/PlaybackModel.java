/* Playback model contract prepared with assistance from OpenAI Codex. */
package comp3011;

import java.io.File;

/**
 * Operations supplied by either playback implementation to the controller.
 */
public interface PlaybackModel {

    /**
     * @return whether audio output is enabled
     */
    boolean isAudioOutputEnabled();

    /**
     * @param file media file to open
     */
    void play(File file);

    /**
     * Seeks to the start.
     */
    void startOver();

    /**
     * Seeks backwards by five seconds.
     */
    void backFiveSeconds();

    /**
     * Seeks forwards by five seconds.
     */
    void forwardFiveSeconds();

    /**
     * Pauses or resumes playback.
     */
    void togglePause();

    /**
     * Enables or disables audio output.
     */
    void toggleAudioOutput();

    /**
     * Stops playback and clears the display.
     */
    void stopPlayback();

    /**
     * Releases playback resources.
     */
    void shutdown();
}
