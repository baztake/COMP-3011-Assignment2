/*
 * Session composition prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Holds effect selections for one playback session.
 * Creates private processor chains before workers begin processing.
 * Decoder and playback lifecycle integration will be added separately.
 */
public class PlaybackSession {
    private final List<FrameEffect> frameEffects;

    /**
     * Stores an immutable snapshot of the selected effects.
     *
     * @param frameEffects effects in command-line order, including repetitions
     * @throws NullPointerException if the list or an effect is null
     */
    public PlaybackSession(List<FrameEffect> frameEffects) {
        this.frameEffects = List.copyOf(frameEffects);
    }

    /**
     * Creates a worker with a fresh, initialised processor chain.
     * Local occurrence counts preserve equivalent seeds across workers.
     *
     * @param videoInfo metadata obtained from the decoded video
     * @return worker owning the newly created processors
     * @throws Exception if processor initialisation fails
     */
    FrameProcessingWorker createWorker(InfoVideo videoInfo) throws Exception {
        Map<FrameEffect, Integer> occurrenceCounts = new EnumMap<>(FrameEffect.class);
        List<FrameProcessor> processors = new ArrayList<>();

        for (FrameEffect effect : frameEffects) {
            int occurrenceIndex = occurrenceCounts.getOrDefault(effect, 0);
            FrameProcessor processor = effect.createProcessor(occurrenceIndex);

            // Initialise before publishing the processor to its worker.
            processor.initialise(videoInfo);
            processors.add(processor);
            occurrenceCounts.put(effect, occurrenceIndex + 1);
        }

        return new FrameProcessingWorker(processors);
    }
}