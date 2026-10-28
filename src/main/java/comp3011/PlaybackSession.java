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
 * Creates bounded pools with worker-private processor chains.
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
     * Creates a bounded pool using this session's selected effects.
     * Each processing thread creates and initialises its own worker on first use.
     * The caller owns pool shutdown and termination waiting.
     *
     * @param workerCount     number of processing threads
     * @param maxQueuedFrames maximum number of accepted, unpolled frames
     * @param videoInfo       metadata obtained from the decoded video
     * @return pool configured to create private, initialised workers
     * @throws IllegalArgumentException if workerCount or maxQueuedFrames is not positive
     */
    FrameProcessingPool createPool(int workerCount, int maxQueuedFrames, InfoVideo videoInfo) {
        return new FrameProcessingPool(workerCount, maxQueuedFrames, () -> {
            try {
                return createWorker(videoInfo);
            } catch (Exception failure) {
                // Supplier cannot throw checked exceptions.
                // The pool reports this failure through the submitted frame's future.
                throw new IllegalStateException("Could not initialise frame processing worker", failure);
            }
        });
    }

    /**
     * Creates a worker with a fresh, initialised processor chain.
     * Local occurrence counts preserve equivalent seeds across workers.
     *
     * @param videoInfo metadata obtained from the decoded video
     * @return worker owning the newly created processors
     * @throws Exception if processor initialisation fails
     */
    private FrameProcessingWorker createWorker(InfoVideo videoInfo) throws Exception {
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