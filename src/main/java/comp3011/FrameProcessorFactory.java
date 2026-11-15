/* Processor chain construction prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds equivalent effect chains without sharing mutable processors between owners. */
final class FrameProcessorFactory {

    private FrameProcessorFactory() {
    }

    /**
     * Preserves command-line order and counts repeated occurrences separately for each effect type.
     * Callers initialise the fresh processors when video metadata becomes available.
     *
     * @param effects selected effects in command-line order
     * @return an unmodifiable list of new processor instances
     */
    static List<FrameProcessor> create(List<FrameEffect> effects) {
        Map<FrameEffect, Integer> occurrenceCounts = new EnumMap<>(FrameEffect.class);
        List<FrameProcessor> processors = new ArrayList<>();

        for (FrameEffect effect : effects) {
            int occurrenceIndex = occurrenceCounts.getOrDefault(effect, 0);
            processors.add(effect.createProcessor(occurrenceIndex));
            occurrenceCounts.put(effect, occurrenceIndex + 1);
        }

        return List.copyOf(processors);
    }
}
