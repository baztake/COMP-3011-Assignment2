/**
 * Assignment 2 additions by Bailey Staykov, a1886621, in collaboration with GPT-6.1 Sol
 */
package comp3011;

/**
 * Available frame effects and their command-line aliases.
 *
 * <p>To add an effect:</p>
 * <ol>
 *     <li>Implement a FrameProcessor subclass and initialise video-dependent state.</li>
 *     <li>Add an enum constant with unique short and long options.</li>
 *     <li>Add its construction case to createProcessor(), creating a separate instance for each occurrence. Pass occurrenceIndex to effects that require independent deterministic seeds.</li>
 *     <li>Add its description to CommandLineController.printHelp().</li>
 *     <li>Update alias, construction and help-output regression tests.</li>
 * </ol>
 *
 * <p>CommandLineController preserves selection order and repetitions.
 * VideoPlayerModel constructs and owns the resulting processor chain.
 * Each selection creates a separate processor instance.</p>
 */
public enum FrameEffect {
    NUMBER('n', "number-frames"),
    SCRATCH('s', "scratch-frames"),
    FLICKER('f', "flicker-frames"),
    BLACK_AND_WHITE('w', "black-and-white"),
    YELLOW('y', "yellow-frames"),
    VIGNETTE('v', "vignette-frames"),
    DUST('d', "dust-frames"),
    JITTER('j', "jitter-frames"),
    MOTTLE('m', "mottle-frames"),
    BLEED('b', "bleed-frames"),
    PEPPER('p', "pepper-frames");

    final char shortOption;
    final String longOption;

    FrameEffect(char shortOption, String longOption) {
        this.shortOption = shortOption;
        this.longOption = longOption;
    }

    /**
     * Identifies one short or long effect option.
     *
     * @param option the option to check
     * @return the matching effect, or null if the option is not recognised
     */
    public static FrameEffect fromOption(String option) {
        for (FrameEffect effect : values()) {
            if (("-" + effect.shortOption).equals(option)
                || ("--" + effect.longOption).equals(option)) {
                return effect;
            }
        }
        return null;
    }

    /**
     * Creates a new processor for this occurrence of the effect.
     * Repeated selections must receive separate processor instances.
     */
    public FrameProcessor createProcessor(int occurrenceIndex) {
        return switch (this) {
            case NUMBER -> new FrameNumberer();
            case SCRATCH -> new FrameScratcher(occurrenceIndex);
            case FLICKER -> new FrameFlickerer(occurrenceIndex);
            case BLACK_AND_WHITE -> new FrameBlackAndWhiter();
            case YELLOW -> new FrameYellower();
            case VIGNETTE -> new FrameVignetter();
            case DUST -> new FrameDuster(occurrenceIndex);
            case JITTER -> new FrameJitterer(occurrenceIndex);
            case MOTTLE -> new FrameMottler(occurrenceIndex);
            case BLEED -> new FrameBleeder();
            case PEPPER -> new FramePepperer(occurrenceIndex);
        };
    }
}
