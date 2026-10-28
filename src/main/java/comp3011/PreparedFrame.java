/*
 * Frame-result contract prepared with assistance from OpenAI Codex.
 */
package comp3011;

import javafx.scene.image.Image;

/**
 * Carries processed output and its preparation metadata.
 * Playback deadlines and logical seek positions remain consumer responsibilities.
 *
 * @param image        processed display image
 * @param info         original frame number and media timestamp
 * @param generation   playback generation associated with the frame
 * @param preparedAtNs monotonic preparation completion time in nanoseconds
 */
public record PreparedFrame(
    Image image,
    InfoFrame info,
    long generation,
    long preparedAtNs
) {
}