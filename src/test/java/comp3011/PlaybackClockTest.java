/*
 * Playback clock regression test developed with assistance from OpenAI Codex.
 */
package comp3011;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackClockTest {

    /**
     * Checks that pausing freezes media time and shifts deadlines on resume.
     * Explicit timestamps avoid sleeps and dependence on thread scheduling.
     */
    @Test
    void shouldFreezeMediaTimeAndShiftDeadlineWhenPaused() {
        // Assemble
        PlaybackClock clock =
                new PlaybackClock(5_000_000L, 1_000_000_000L);
        long frameTimestampUs = 5_040_000L;

        // Act
        long originalDeadline = clock.targetTimeNs(frameTimestampUs);

        // Pause after 20 milliseconds of playback.
        clock.setPaused(true, 1_020_000_000L);
        clock.setPaused(true, 1_030_000_000L);
        long pausedMediaTime = clock.mediaTimestampUs(2_000_000_000L);
        boolean dueWhilePaused =
                clock.isDue(frameTimestampUs, 2_000_000_000L);

        // Resume after a one-second pause.
        // Repeating either state must not change the timing again.
        clock.setPaused(false, 2_020_000_000L);
        clock.setPaused(false, 2_025_000_000L);
        long resumedDeadline = clock.targetTimeNs(frameTimestampUs);
        long resumedMediaTime = clock.mediaTimestampUs(2_030_000_000L);
        boolean dueBeforeDeadline =
                clock.isDue(frameTimestampUs, 2_039_999_999L);
        boolean dueAtDeadline =
                clock.isDue(frameTimestampUs, 2_040_000_000L);

        // Assert
        assertThat(originalDeadline).isEqualTo(1_040_000_000L);
        assertThat(pausedMediaTime).isEqualTo(5_020_000L);
        assertThat(dueWhilePaused).isFalse();
        assertThat(resumedDeadline).isEqualTo(2_040_000_000L);
        assertThat(resumedMediaTime).isEqualTo(5_030_000L);
        assertThat(dueBeforeDeadline).isFalse();
        assertThat(dueAtDeadline).isTrue();
    }
}