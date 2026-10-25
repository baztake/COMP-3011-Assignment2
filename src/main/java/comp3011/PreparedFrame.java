package comp3011;

import javafx.scene.image.Image;

public record PreparedFrame(
    Image image,
    InfoFrame info,
    long generation
) {
}
