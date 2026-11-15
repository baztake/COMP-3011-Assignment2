/* Subtitle support prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import java.util.Comparator;
import java.util.List;

/** Immutable cues safe to share between workers. Lookup also supports backwards seeking. */
public final class SubtitleTrack {

    private final List<SubtitleCue> cues;

    /**
     * Sorts and snapshots cues, rejecting ambiguous overlapping intervals.
     *
     * @param cues formatted subtitle cues
     * @throws IllegalArgumentException if cues overlap
     */
    public SubtitleTrack(List<SubtitleCue> cues) {
        this.cues = cues.stream().sorted(Comparator.comparingLong(SubtitleCue::startUs)).toList();
        for (int index = 1; index < this.cues.size(); index++) {
            if (this.cues.get(index - 1).endUs() > this.cues.get(index).startUs()) {
                throw new IllegalArgumentException("Subtitle cues must not overlap");
            }
        }
    }

    /**
     * @param timestampUs position from the beginning of the media in microseconds
     * @return the active cue, or null during silence
     */
    public SubtitleCue findCue(long timestampUs) {
        int low = 0;
        int high = cues.size() - 1;
        // Search from the timestamp each time, so seeking backwards needs no cursor reset.
        while (low <= high) {
            int middle = low + (high - low) / 2;
            SubtitleCue cue = cues.get(middle);
            if (timestampUs < cue.startUs()) {
                high = middle - 1;
            } else if (timestampUs >= cue.endUs()) {
                low = middle + 1;
            } else {
                return cue;
            }
        }

        return null;
    }

    /** @return whether this track contains no speech cues */
    public boolean isEmpty() {
        return cues.isEmpty();
    }

}
