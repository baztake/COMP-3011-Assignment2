/**
 * Assignment 2 additions by Bailey Staykov (a1886621)
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;

/**
 * An enum representing the different frame effects that can be applied to a video.
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
     * Parses a command-line argument string and returns a list of FrameEffect enums corresponding to the specified options.
     *
     * @param arg the command-line argument string to parse
     * @return a list of FrameEffect enums corresponding to the specified options
     */
    public static List<FrameEffect> parseArgs(String arg) {
        List<FrameEffect> parsedEffects = new ArrayList<>();
        if (arg == null || !arg.startsWith("-")) {
            return parsedEffects;
        }

        if (arg.startsWith("--")) {
            for (FrameEffect effect : values()) {
                if (arg.equals("--" + effect.longOption)) {
                    parsedEffects.add(effect);
                    return parsedEffects;
                }
            }
            return parsedEffects;
        }

        for (int i = 1; i < arg.length(); i++) {
            char option = arg.charAt(i);
            FrameEffect matched = null;

            for (FrameEffect effect : values()) {
                if (effect.shortOption == option) {
                    matched = effect;
                    break;
                }
            }

            if (matched == null) {
                return List.of();
            }
            parsedEffects.add(matched);
        }

        return parsedEffects;
    }
}
